# 项目功能增强总览

本文件记录 mall-swarm 在原版基础上做的功能优化与并发防护增强，按功能模块组织，每个功能包含：解决的问题 → 技术方案 → 关键文件 → 验证结果。

## 技术栈总览

| 技术 | 用在哪 |
| --- | --- |
| Redis（Spring Data Redis + Redisson） | 防重令牌、缓存三兄弟、秒杀库存/限购/限流、延时关单 |
| Redisson 分布式锁 / 信号量 / 原子计数器 / Lua 脚本 | 缓存防击穿互斥锁、秒杀三层防护、demo |
| RabbitMQ TTL 队列 + 死信交换机 | 订单超时自动关单 |
| 条件 UPDATE 原子 SQL（DB 层乐观锁） | 锁库存防超卖、秒杀兜底 |
| Sa-Token | 网关层统一鉴权（既有体系，新功能全部复用） |

## 1. 订单提交防重令牌

- **解决的问题**：用户双击/重复提交导致同一订单重复下单
- **方案**：确认订单页生成 UUID 令牌存 Redis（带过期时间），提交订单时用 `getAndDelete` 原子取走并校验，取不到即拒绝
- **关键文件**：
  - 后端：`mall-portal/OmsPortalOrderServiceImpl`（生成/校验）、`mall-common/RedisService`（新增 getAndDelete）
  - 前端：`mall-app-web/src/pages/order/createOrder.vue`、`types/order.d.ts`
- **验证**：令牌一次性消费，二次提交直接被拒

## 2. 并发锁库存防超卖（原子 SQL）

- **解决的问题**：并发下单时"读-判断-写"三步分离导致超卖
- **方案**：下单锁库存改用条件 UPDATE——`lock_stock + q WHERE stock - lock_stock >= q`，把判断和扣减收敛为数据库内一条原子语句，受影响 0 行即库存不足
- **关键文件**：`mall-portal/dao/PortalOrderDao.xml`（lockSkuStock）、`OmsPortalOrderServiceImpl.lockStock`（0 行即失败）
- **验证**：可售数 = stock - lock_stock 模型下并发下单无超卖

## 3. RabbitMQ 延时关单 + 消费失败重试与死信兜底

- **解决的问题**：① 超时未支付订单自动关单、释放锁定库存；② 原消费异常会无限 requeue，毒消息堵死队列
- **方案**：下单发 TTL 消息（每单独立过期时间）→ 到期死信转发 → 幂等关单（仅 status=0）并释放库存/退券/还积分；消费失败自动重试 3 次（2s/4s 退避），耗尽由 `RepublishMessageRecoverer` 转发死信队列供人工排查
- **关键文件**：`mall-portal/config/RabbitMqConfig.java`（自定义 rabbitListenerContainerFactory）、`component/CancelOrderSender/Receiver`、`domain/QueueEnum`
- **验证**：毒消息 3 次尝试后落入 `mall.order.cancel.dlq`（消息头带异常堆栈）；合法消息正常消费；TTL→死信→消费全链路畅通、队列零积压

## 4. 秒杀下单闭环

- **解决的问题**：高并发抢购下的限流、防超卖、防重复购买
- **方案**：三层防超卖——RSemaphore 每场次信号量限流（挡流量）→ Redis Lua 原子扣秒杀额度（第一道正确性）→ lockSkuStock 条件 UPDATE（第二道 DB 防线）；一人一单用 RAtomicLong 计数；秒杀库存 SETNX 懒加载预热；订单 orderType=1、秒杀价，超时用 flashOrderOvertime；任何失败按标记位精确回滚 Redis、DB 事务回滚
- **关键文件**：`mall-portal/controller/FlashPromotionOrderController.java`、`service/impl/FlashPromotionOrderServiceImpl.java`、`config/RedissonConfig.java`（手动装配，规避 starter 抢占 Spring Data Redis 连接工厂）
- **设计文档**：`docs/superpowers/specs/2026-09-04-seckill-sync-design.md`
- **验证**：三轮 100 并发压测（10 件库存）均为 10 成功/90 抢完，Redis 库存归零、订单数与锁定库存数三层一致、零超卖；60 分钟超时后 21 单全部自动关单并释放锁定库存

## 5. 商品详情缓存三兄弟

- **解决的问题**：缓存穿透（查不存在的数据）、击穿（热点 key 过期瞬间并发回源）、雪崩（大量 key 同时过期），以及改商品后缓存脏数据
- **方案**：互斥锁 + 双重检查防击穿（全系统同一时刻只有一个线程回源）；空值缓存（`"NULL"` 哨兵，5 分钟）防穿透；过期时间 30 分钟 + 随机 0~5 分钟防雪崩；admin 更新/删除商品后删缓存 key（失效策略）
- **关键文件**：`mall-portal/PmsPortalProductServiceImpl.detail`（缓存外壳 + getDetailFromDb）、`mall-admin/PmsProductServiceImpl`（缓存失效）
- **验证**：三轮测试 + 一次重启——首查 7+ 次 SQL 回源、二查 0 次；不存在 id 空值缓存 300 秒 0 回源；DEL 后 20 并发恰好 1 次回源；admin 改商品缓存立即失效

## 6. ES 检索增强（mall-search）

- **解决的问题**：搜索只支持关键词+品牌+分类；筛选面板与商品范围脱节；商品上下架后索引靠手动 importAll
- **方案**：
  - 属性筛选 + 价格区间：nested 查询（attrValueList 三个条件落在同一条属性记录）+ range 查询 number 变体，均在 filter 上下文不干扰评分
  - 聚合联动筛选：品牌/分类筛选条件并入 query，聚合基于筛选后结果集收缩
  - 上下架 MQ 自动同步：admin 改 publish_status 后发 JSON 消息，search 消费——上架建索引、下架删索引
- **关键文件**：
  - mall-search：`EsProductServiceImpl.search/searchRelatedInfo`、`EsProductController`、新增 `RabbitMqConfig`、`ProductSyncReceiver`
  - mall-admin：`PmsProductServiceImpl.updatePublishStatus`（发消息）、新增 `RabbitMqConfig`
  - mall-common 新增：`ProductSyncMessage`、`SearchQueueEnum`（跨服务共享契约）
- **验证**：8 用例全过且与 ES 原生 DSL 对照一致；聚合随 brandId 收缩（8 品牌→1 品牌）；下架 3 秒 ES 404、上架 3 秒恢复

## 7. Sentinel 限流熔断 + 网关令牌桶限流

- **解决的问题**：无入口流量治理——任何接口可被单用户打爆；无服务层热点接口保护；无异常自动熔断
- **方案**：双层限流——网关层 RequestRateLimiter 令牌桶（按 IP，20 QPS/burst 40，Redis 存桶），服务层 Sentinel（商品详情 QPS=5、秒杀接口流控 + 异常比例熔断 + 半开恢复）；业务异常透传（ApiException 原样返回）、限流/熔断文案分流（DegradeException 分支）
- **关键文件**：
  - mall-gateway：`application.yml`（路由挂 RequestRateLimiter + 修复 routes 层级/关闭动态路由）、新增 `RequestRateLimiterConfig`（ipKeyResolver）
  - mall-portal：`PmsPortalProductController`、`FlashPromotionOrderController`（@SentinelResource + blockHandler/fallback）、pom/yml
  - 根目录：`sentinel-dashboard.bat`（控制台 8858，jar 不入库）
- **验证**：网关 100 并发 72 通/28 拦（429）；Sentinel 30 并发 8 通/22 拦；熔断状态机（打开→快速失败→半开→恢复）实测走通；限购异常透传"每人限购1件"
- **踩坑**：gateway yml 的 routes 层级错误导致显式路由从未加载；动态路由与显式路由同 id 覆盖 filter；Sentinel 网关适配器与 Gateway 2025 不兼容（改用令牌桶方案）；Dashboard 自身占用 8719 端口；`eager`/`filter.enabled` 默认关闭

## 8. 商品详情 CompletableFuture 异步编排

- **解决的问题**：缓存 miss 回源时 8 次 DB 查询全串行，回源慢、击穿时唯一回源线程占用互斥锁时间长
- **方案**：product 主查询作串行根 → 6 路互不依赖查询 `supplyAsync` 并行（brand/attributes/sku/ladder/满减/coupons）→ 属性值用 `thenApplyAsync` 二级编排挂在 attributes 之后 → `allOf().join()` 统一等待 → 汇总组装；独立业务线程池（core 8/max 16/队列 100/CallerRunsPolicy，线程名 businessPool-*），不用共享的 ForkJoinPool.commonPool
- **关键文件**：新增 `mall-portal/config/BusinessThreadPoolConfig.java`；修改 `PmsPortalProductServiceImpl.getDetailFromDb`
- **验证**：同一次回源 6+ 条 SQL 分布在 5 个不同 businessPool 线程（日志线程名证实）；回源 356ms；与缓存三兄弟、互斥锁回源形成完整配合链

## 9. 可靠消息投递（本地消息表 Outbox）

- **解决的问题**：商品上下架"改DB→发MQ"两步裸奔——MQ失败消息消失、ES永不同步，且RabbitMQ对不存在交换机是静默丢弃（convertAndSend不抛异常）
- **方案**：Transactional Outbox——事务内"改DB+消息落表"同生共死 → afterCommit（registerSynchronization）真正发MQ → Publisher Confirms确认到达才markSent，nack/退回则retry+1 → 定时任务每30秒扫描"待发送且重试<5"补发；mandatory=true防静默丢弃、退回集合防"先return后ack"时序覆盖
- **关键文件**：
  - mall库新增 `mall_local_message` 表
  - mall-admin 新增：`LocalMessageDao`+XML、`LocalMessageService`+Impl、`LocalMessageResendTask`；修改：`RabbitMqConfig`（可靠RabbitTemplate）、`PmsProductService/Impl`（@Transactional + outbox改造）、启动类 @EnableScheduling
  - mall-search：`ProductSyncReceiver` 改按Map字段解析（跨服务解耦）
- **验证**：正常上下架消息落表→确认→status=1→ES同步；错误交换机消息保持status=0且retry递增；修正后30秒内自动补发成功
- **踩坑**：AmqpTemplate接口无CorrelationData重载（在RabbitTemplate上）；Broker先return后ack需防时序覆盖；IDEA半成品class混入jar需clean打包

## 10. 登录安全增强（失败锁定）

- **解决的问题**：登录接口无失败限制，暴力破解敞口（无限试密码）
- **方案**：Redis 失败计数锁定——每用户名一个计数器（`ums:admin:loginFail:{username}`），失败+1且重置10分钟过期，满5次直接拒绝（正确密码也挡）；登录成功清零；到期自动解锁
- **关键文件**：`mall-admin/UmsAdminServiceImpl.login`（锁定检查+4个失败分支计数+成功清零+recordLoginFail）
- **验证**：5次错误密码后第6次正确密码被拒"登录失败次数过多"；DEL解锁后登录成功且计数清零
- **扩展**：portal 会员登录可套同款逻辑（UmsMemberServiceImpl.login）

## 11. Token 双凭证与自动换发（accessToken + refreshToken）

- **解决的问题**：accessToken 有效期 7 天过长（泄露窗口大），缩短后用户频繁重新登录体验差
- **方案**：双凭证——accessToken 2 小时（Sa-Token timeout: 7200）+ refreshToken 7 天（UUID 随机串存 Redis，key `ums:admin:refreshToken:{UUID}` value=loginId）。前端 axios 拦截器捕获 401 后自动调 `/auth/refresh` 换发（旧 refreshToken 一次性作废、滚动换新），成功则用新 token 重试原请求（用户无感），失败才跳登录页
- **关键文件**：admin `UmsAdminServiceImpl`（登录签发+换发）、`UmsAdminController`、auth `AuthController`/Feign；前端 `utils/http.ts`（401 换发重试）、`stores/user.ts`、`types/admin.d.ts`、env 加 `VITE_AUTH_SERVER_URL`
- **验证**：换发成功返回新双凭证；旧 refreshToken 二次换发被拒（防重放）；新 accessToken 的 Redis TTL=7199 秒
- **设计要点**：凭证类 Redis key 用凭证本身（不可猜），服务端档案类才用 id 做 key；refreshToken 每次换发滚动更新，凭证暴露面最小化

## 12. 自动化单元测试（JUnit 5 + Mockito）

- **解决的问题**：此前所有验证靠手工压测，无自动化质量保障
- **方案**：全 Mock 单元测试覆盖两个最复杂模块的业务分支——秒杀下单 6 用例（校验/限购/库存/成功/锁库存失败的回滚补偿精确断言）、商品详情缓存三兄弟 7 用例（命中/空值命中/双重检查/回源写缓存随机过期区间断言/拿锁失败重读/查无写空值/快速失败）
- **关键文件**：`mall-portal/src/test/java/com/macro/mall/portal/service/FlashPromotionOrderServiceImplTest.java`、`PmsPortalProductServiceImplTest.java`
- **验证**：13 用例全绿（mvn -pl mall-portal test -DskipTests=false -Dtest=...）
- **踩坑**：Mockito 严格模式对公共 setUp stub 报 UnnecessaryStubbing（类级 LENIENT）；void 方法必须 doAnswer().when() 语法；Redisson RBucket 泛型 mock 用原始类型；异步 Executor 需同步执行替身防 Future 挂起

## 13. Seata 分布式事务演示（AT 模式）

- **解决的问题**：学习分布式事务落地——单库项目无真实跨服务事务场景，以 demo 模块演示全局事务的完整机制
- **方案**：本机部署 Seata Server 2.0.0（TC，8091 RPC/7091 控制台，file 存储，Maven 中央 jar 组装运行于 D:\seata-run）；mall 库建 undo_log 表；demo 服务 @GlobalTransactional 发起（本地写品牌 + Feign 调 portal 写品牌），portal 作为 RM 参与者；失败分支全局回滚、成功分支两端提交
- **关键文件**：`mall-demo/SeataDemoServiceImpl`（@GlobalTransactional + Feign + 业务码检查）、`mall-demo/SeataDemoController`、`mall-portal/SeataDemoController`、两服务 pom/yml（seata-spring-boot-starter + tx-service-group + grouplist）、根目录 seata-server.bat
- **验证**：success=false 时接口 500 且 demo 记录数 4→4 不变（回滚生效）；success=true 时两端各 +1
- **踩坑**：TC 部署网络封锁（最终用 Maven jar 组装方案）；2.x 的 server.port 是控制台端口而 RPC 端口是 seata.server.service-port；file 注册模式必须显式配 grouplist；**Feign 对 HTTP 200+code 500 不抛异常，调用方必须检查业务码否则全局事务"假装成功"**

## 14. Redisson 学习 demo（mall-demo）

- **内容**：① 基础使用——20 线程并发自增计数器，无锁版丢更新（<20）vs 加锁版精确（=20）；② 业务场景——分布式锁 + DB 乐观锁并发扣库存，无锁版复现超卖、锁+乐观锁版精确扣减
- **关键文件**：`mall-demo/service/impl/RedissonDemoServiceImpl.java`、`RedissonStockDemoServiceImpl.java`、`dao/SkuStockDao.java`
- **验证**：100 并发扣 100 库存分毫不差；50 并发无锁版成功 50 次却只扣 1 件（超卖复现）

## 踩坑记录（避坑指南）

| 坑 | 解决 |
| --- | --- |
| redisson-spring-boot-starter 抢占 Spring Data Redis 的 ConnectionFactory 导致启动失败 | 改用纯客户端手动装配 RedissonConfig |
| RBucket 默认 Kryo 二进制序列化，Lua 脚本 `tonumber` 读不到 | 与 Lua 交互必须显式 `StringCodec.INSTANCE` |
| 秒杀场次时间是 TIME 类型（每天循环的时段），直接和完整日期比较永远失败 | 用 `DateUtil.getTime()` 归一化到时分秒再比 |
| 订单 `receiver_*` 字段非空，秒杀下单未填导致约束冲突 | 从收货地址服务查出后填充 |
| 构建/运行必须 JDK 17（本机默认 JAVA_HOME 是 1.8） | 显式指定 `JAVA_HOME` |
