# mall-swarm

mall 商城全家桶：Spring Cloud 微服务后端 + 后台管理前端 + 移动端，单仓库管理三个子项目。

## 子项目

| 目录 | 说明 |
| --- | --- |
| [mall-swarm-backend](mall-swarm-backend/) | Spring Cloud 微服务后端（fork 自 macrozheng/mall-swarm，已升级 Spring Cloud 2025 + Spring Boot 3.5，认证由 OAuth2 迁移为 Sa-Token） |
| [mall-admin-web](mall-admin-web/) | 后台管理前端，Vue 3 + TypeScript + Vite + Element Plus |
| [mall-app-web](mall-app-web/) | 移动端，uni-app（Vue 3）一套代码多端：H5 / 微信小程序 / App |

## 快速开始

1. 初始化数据库：执行 [mall.sql](mall-swarm-backend/document/sql/mall.sql)（单库 mall）
2. 启动基础设施（MySQL、Redis、Nacos、MinIO 等）：
   `docker compose -f mall-swarm-backend/document/docker/docker-compose-env.yml up -d`
3. 启动后端：在 `mall-swarm-backend/` 下用 JDK 17 执行 `mvn clean package` 后逐个启动服务（或 IDE 直接运行；默认 JAVA_HOME 为 JDK 8 时会构建失败）
4. 启动前端：
   - 后台管理：`cd mall-admin-web && npm install && npm run dev`
   - 移动端 H5：`cd mall-app-web && npm install && npm run dev:h5`

## 服务端口

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| mall-gateway | 8201 | 统一网关（WebFlux），网关层 Sa-Token 鉴权 |
| mall-auth | 8401 | 认证中心，签发 token |
| mall-admin | 8080 | 后台管理服务 |
| mall-portal | 8085 | 前台商城服务（含 /sso 登录接口） |
| mall-search | 8081 | 商品搜索 |
| mall-demo | 8082 | Feign 远程调用示例 + Redisson 分布式锁 demo |
| mall-monitor | 8101 | Spring Boot Admin 监控中心 |

> 注意：开发环境下两个前端通过网关访问后端（admin-web → gateway:8201/mall-admin，app-web → gateway:8201/mall-portal）。

## 更多信息

- 后端细节：[mall-swarm-backend/CLAUDE.md](mall-swarm-backend/CLAUDE.md)
- 后台管理前端细节：[mall-admin-web/CLAUDE.md](mall-admin-web/CLAUDE.md)
- 移动端细节：[mall-app-web/CLAUDE.md](mall-app-web/CLAUDE.md)
