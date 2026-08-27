# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

mall-swarm 商城移动端（fork 自 macrozheng/mall-app-web），uni-app（Vue 3 + TypeScript + Vite，@dcloudio 3.x）+ Pinia 2 + uni-ui，一套代码编译到 H5 / 微信小程序 / App 多端。后端为同目录下的 `mall-swarm-backend`，本项目接口全部在 mall-portal 模块中。

## 常用命令

- 开发（H5）：`npm run dev:h5`（即 `uni`，默认 http://localhost:5173，浏览器调试）
- 开发（微信小程序）：`npm run dev:mp-weixin`（产物 dist/dev/mp-weixin，用微信开发者工具打开）
- 开发（App）：`npm run dev:app` / `dev:app-android` / `dev:app-ios`
- 构建：`npm run build:h5` / `build:mp-weixin` / `build:app`（产物在 dist/build/ 下）
- 类型检查：`npm run tsc`（vue-tsc --noEmit）
- Lint：`npm run lint`；格式化：`npm run format`（prettier）
- 无测试框架，无单测

## 架构与关键机制

- **请求链路**：不用 axios，基于 `uni.request` 封装在 `src/utils/http.ts`（全局 request/uploadFile 拦截器 + `http<T>()` 函数）。baseURL 来自 `VITE_API_BASE_URL`（开发环境 http://localhost:8085），**直连 mall-portal 服务，不走 gateway（8201）**，路径如 `/sso/login`、`/sso/info`（对应 mall-portal 的 UmsMemberController）。响应统一 `CommonResult{code,message,data}`：code==200 成功，其余 toast 提示；401 自动清登录态并跳登录页。
- **认证**：登录返回 `tokenHead + token`（对应后端 Sa-Token 的 Bearer 前缀），拼接后 `uni.setStorageSync('token')` 存本地，拦截器自动加 `Authorization` 头和 `source-client: miniapp` 标识头。会员信息存 member pinia store（persist 持久化）。
- **页面路由**：uni-app 约定式，所有页面和 tabBar 都注册在 `src/pages.json`，新增页面必须在此登记。组件走 easycom 自动扫描注册（`src/components/*.vue` 平铺即可用，无需 import）；uni-ui 按 `uni-*` 前缀自动映射。
- **多端兼容**：页面内用 `#ifdef H5` / `#ifndef MP-WEIXIN` 等条件编译注释区分平台（cart、pay、index、product 等页均有使用），不要用运行时浏览器 API 判断平台。支付宝支付仅 H5 端启用（`VITE_USE_ALIPAY`，开发环境默认 true）；小程序 appid、H5 路由 base 等配置在 `src/manifest.json`。
- 商品图等静态资源放 `src/static/`。

## 约定

- API 定义按业务模块放 `src/apis/`（如 order.ts、cart.ts），类型放 `src/types/`，页面放 `src/pages/` 对应业务子目录。
- 环境变量以 `VITE_` 开头：.env 存通用默认值，.env.development / .env.production 按模式覆盖。
- Pinia 固定 2.0.27（uni-app 兼容性要求），升级需谨慎。
