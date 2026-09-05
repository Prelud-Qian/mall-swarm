# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

mall-swarm 微服务商城（fork 自 macrozheng/mall-swarm），已升级 Spring Cloud 2025 + Spring Boot 3.5，认证由 Spring Security OAuth2 迁移为 Sa-Token。

## 常用命令

- 全量构建（默认跳过测试）：`mvn clean package`
- 构建单模块（含依赖模块）：`mvn -pl mall-admin -am clean package`
- 运行测试：`mvn test -DskipTests=false`（root pom 默认 `skipTests=true`）
- 本地运行单服务：`mvn -pl mall-admin spring-boot:run` 或 `java -jar mall-admin/target/mall-admin-1.0-SNAPSHOT.jar`
- 启动基础设施：`docker compose -f document/docker/docker-compose-env.yml up -d`
- Docker 镜像：各服务模块已配置 docker-maven-plugin，`mvn package` 时构建并推送到 `docker.host` 指定的远程 Docker（默认 http://192.168.3.101:2375，用 `-Ddocker.host=` 覆盖）
- 重新生成 MBG 代码：运行 mall-mbg 模块 `Generator.main()`（配置见 `mall-mbg/src/main/resources/generator.properties`）

## 架构

### 模块（root pom 的 modules 顺序即构建顺序）

| 模块 | 端口 | 说明 |
| --- | --- | --- |
| mall-common | - | 共享代码：通用响应/分页对象（api 包）、annotation、exception、log、RedisService 等 |
| mall-mbg | - | MyBatis Generator 生成的 mall 库 model + mapper，含代码生成器 |
| mall-gateway | 8201 | Spring Cloud Gateway（WebFlux）统一入口，网关层 Sa-Token 鉴权 |
| mall-auth | 8401 | 认证中心：登录/注册、签发 token |
| mall-admin | 8080 | 后台管理服务（MyBatis + Druid + PageHelper） |
| mall-portal | 8085 | 前台商城（MySQL + MongoDB + RabbitMQ） |
| mall-search | 8081 | 商品搜索（spring-data-elasticsearch） |
| mall-demo | 8082 | Feign 远程调用示例 + Redisson 分布式锁 demo |
| mall-monitor | 8101 | Spring Boot Admin 监控中心（只注册 Nacos，不读 Nacos 配置） |

### 关键机制

- **注册与配置**：所有服务注册到 Nacos（默认 localhost:8848）。dev 环境通过 `spring.config.import: nacos:mall-{service}-dev.yaml` 从 Nacos 配置中心拉配置（dataId 格式固定）；`config/{service}/` 目录是这些配置的导出副本，改动需两边同步。mall-monitor 只注册不读配置。
- **认证链路**：mall-auth 用 OpenFeign 调 mall-admin（UmsAdminService）/ mall-portal（UmsMemberService）校验账号，签发 Sa-Token（token-name: `Authorization`、前缀 `Bearer`、存 Redis，sa-token-redis-jackson + JWT）。mall-gateway 用 sa-token-reactor 在网关层拦截校验：白名单在 gateway 的 `secure.ignore.urls`，路由拦截见 `SaTokenConfig`，权限加载见 `StpInterfaceImpl`。
- **订单防重令牌**：确认订单页 `preGenerateOrder` 生成 UUID 令牌存 Redis（key 前缀、过期时间由 `redis.key.orderToken`、`redis.expire.orderToken` 配置），提交订单 `generateOrder` 用 `RedisService.getAndDelete` 原子取走并校验，取不到即拒绝，防重复下单。
- **并发锁库存（原子 SQL）**：下单 `lockStock` 对每个商品执行 `PortalOrderDao.lockSkuStock`（条件 UPDATE：`lock_stock = lock_stock + q WHERE stock - lock_stock >= q`），受影响 0 行即库存不足；支付成功 `updateSkuStock` 同时扣真实库存与锁定库存，取消/超时 `releaseSkuStockLock` 只释放锁定。可售数 = `stock - lock_stock`。
- **订单超时关单（RabbitMQ 延时队列）**：下单后 `sendDelayMessageCancelOrder` 发 TTL 消息（每单独立过期时间）→ 到期死信转发 `mall.order.cancel` → `CancelOrderReceiver` → `cancelOrder` 幂等关单（仅 status=0）并释放锁定库存、退券还积分；秒杀订单按 `orderType` 用 `flashOrderOvertime`。消费失败自动重试 3 次（2s/4s 退避），耗尽由 `RepublishMessageRecoverer` 转发死信队列 `mall.order.cancel.dlq`（`RabbitMqConfig` 自定义 `rabbitListenerContainerFactory`，关闭了默认无限 requeue）。`OrderTimeOutCancelTask` 定时任务是遗留备用代码（已注释）。
- **秒杀下单（mall-portal）**：`POST /flashPromotion/order/generate`，管理端秒杀配置复用 `sms_flash_promotion_*` 四表。三层防超卖：`RSemaphore` 每场次信号量限流 → Redis Lua 原子扣额度（`seckill:stock:{relationId}`，SETNX 懒加载预热）→ `lockSkuStock` 条件 UPDATE 兜底；一人一单用 `RAtomicLong` 计数（超限回滚 -1）；订单 `orderType=1` 秒杀价，超时用 `flashOrderOvertime`。场次时间是 TIME 类型，比较需 `DateUtil.getTime()` 归一化。
- **Redisson demo（mall-demo）**：`/redisson/noLock`、`/redisson/withLock`（20 线程并发自增计数器，无锁版丢更新、加锁版精确）；`/redisson/stockDeductWithoutLock`（超卖对照）、`/redisson/stockDeductWithLock`（分布式锁 + DB 乐观锁精确扣减，乐观锁 SQL 见 `SkuStockDao.deductStock`）、`/redisson/stockReset`（重置库存，便于反复演示）。
- **路由规则**：gateway 按 `/mall-{service}/**` 前缀 `lb://` 路由并 `StripPrefix=1`。
- **API 文档**：Knife4j 在 gateway 聚合（discover 模式，排除 mall-monitor），入口 http://localhost:8201/doc.html；各服务另配 springdoc。
- **数据库**：单库 mall（初始化脚本 `document/sql/mall.sql`），业务模块 Mapper XML 位于 `classpath:dao/*.xml` 和 `classpath*:com/**/mapper/*.xml`。
- **部署**：`document/docker/`（compose + nginx）、`document/k8s/`（Deployment/Service）、`document/sh/`（启动脚本）；容器内以 prod profile 启动。

## 约定

- 依赖版本统一在 root pom 的 `properties` 管理（Spring Boot 3.5.14 / Spring Cloud 2025.0.2 / Spring Cloud Alibaba 2025.0.0.0），不要在子模块单独写版本号。
- Java 17；编译统一加 `-parameters`（Spring Boot 3.2+ 参数名保留要求）。本机默认 `JAVA_HOME` 指向 JDK 8，直接 `mvn` 构建会报"无效的目标发行版: 17"，需显式指定 JDK 17（如 `JAVA_HOME="$HOME/.jdks/ms-17.0.15"`）再构建/运行。
- Redisson 在 mall-portal 用**纯客户端手动装配**（`RedissonConfig` 手动建 Bean，读 `spring.data.redis.*`），不要用 `redisson-spring-boot-starter`——其自动配置会抢占 Spring Data Redis 的 `RedisConnectionFactory` 导致启动失败；`RBucket` 与 Lua 脚本交互时必须显式 `StringCodec.INSTANCE`（默认 Kryo 二进制，Lua 的 `tonumber` 读不到）。
- 每服务三份配置：`application.yml`（端口、通用配置）、`application-dev.yml`（本地 + Nacos config import）、`application-prod.yml`（生产）。
- 本地默认依赖：MySQL root/root、Redis 无密码、Nacos localhost:8848、MinIO minioadmin/minioadmin。
- 本地仓库使用阿里云 Maven 镜像加速（root pom 已配置）。
