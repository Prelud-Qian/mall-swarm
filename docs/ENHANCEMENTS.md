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

## 6. Redisson 学习 demo（mall-demo）

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
