# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

mall-swarm 商城后台管理前端（fork 自 macrozheng/mall-admin-web master 分支，即 Vue3 重写版；原 Vue2 版在 dev-v2 分支）。技术栈：Vue 3 + TypeScript + Vite 7 + Element Plus 2 + Pinia 3，Node 要求 `^20.19.0 || >=22.12.0`。后端为同目录下的 `mall-swarm-backend`。

## 常用命令

- 启动开发：`npm run dev`（Vite 默认 http://localhost:5173）
- 类型检查：`npm run type-check`（vue-tsc --build）
- 构建：`npm run build`（先 type-check 再 vite build，npm-run-all2 串联）
- 仅打包：`npm run build-only`
- Lint（自动修复）：`npm run lint`（ESLint 9 flat config，见 eslint.config.ts）
- 无测试框架，无单测

## 架构与关键机制

- **请求链路**：axios 实例封装在 `src/utils/http.ts`，baseURL 来自 `VITE_BASE_SERVER_URL`（开发环境 http://localhost:8080），**直连 mall-admin 服务，不走 gateway（8201）**，路径无 `/mall-admin` 前缀（如 `/admin/login`）。响应统一 `CommonResult{code,message,data}`：code==200 拦截器直接返回 data，其余弹错误提示；401 弹登出确认框。
- **认证**：登录返回 `tokenHead + token`（对应后端 Sa-Token 的 Bearer 前缀），拼接后存 user pinia store（persist 持久化），请求拦截器自动携带 `Authorization` 头。
- **权限与菜单**：路由分 constantRouterMap + asyncRouterMap 两张静态表（`src/router/index.ts`），路由 name 与后端 ums_menu 的 name 一一对应。登录后 permission store 用后端返回的 menus 过滤 asyncRouterMap 再动态 addRoute，菜单标题/图标/排序/显隐以后端数据为准；守卫在 `src/router/guard.ts`。
- **UI 与主题**：Element Plus 组件与 API 由 unplugin-auto-import / unplugin-vue-components 自动导入，组件内无需手动 import；ElMessage、ElMessageBox 等仍需显式 import（见 http.ts）。主题色用 scss 覆盖实现：vite 里 additionalData 向每个 scss 文件注入 `@/styles/element/index.scss` 和 `@/styles/var.scss`，改主题色改 var.scss——组件样式必须写在 `<style lang="scss">` 里注入才生效。
- **图标**：SVG 文件放 `src/icons/svg/`，vite-plugin-svg-icons 注册为 symbol，通过 SvgIcon 组件（icon-class 属性）引用，无需手动注册。
- **上传**：`VITE_USE_OSS` 控制走 OSS 还是 MinIO（singleUpload/multiUpload/Tinymce 三处统一判断），MinIO 地址 = 基础地址 + `VITE_MINIO_UPLOAD_URL`（/minio/upload）。
- **路由与部署**：hash 模式（createWebHashHistory），`base: './'` 支持任意路径部署。
- 富文本 tinymce 6（封装于 components/Tinymce），图表 echarts 6 + vue-echarts 8。

## 约定

- API 定义放 `src/apis/`（按业务模块一个文件，如 product.ts、order.ts），类型放 `src/types/`（.d.ts），页面放 `src/views/{oms|pms|sms|ums}/`。
- 新增菜单页面时路由要加在 asyncRouterMap 中且 name 与后端菜单 name 一致，只加 views 不会显示。
- 环境变量以 `VITE_` 开头：.env 存通用默认值，.env.development / .env.production 按模式覆盖。
