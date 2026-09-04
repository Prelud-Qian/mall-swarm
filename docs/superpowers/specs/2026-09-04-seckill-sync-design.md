# 秒杀第一版（同步版闭环）设计

日期：2026-09-04
状态：已确认，待实现

## 背景

管理端（mall-admin）已有秒杀活动/场次/商品关联的完整 CRUD（4 张表：sms_flash_promotion、sms_flash_promotion_session、sms_flash_promotion_product_relation、sms_flash_promotion_log），门户首页已有秒杀展示（HomeDao.getFlashProductList）。缺失的是**秒杀购买链路**。

## 范围

第一版在 mall-portal 内实现同步版秒杀下单闭环：Redis 预扣库存 + Redisson 限流 + 一人一单 + 秒杀订单落库 + 复用现有库存锁定与延时关单链路。不做：管理端预热定时任务、MQ 异步下单、库存工作单、前端秒杀入口、网关限流。

## 调用链

```
POST /flashPromotion/order/generate（参数：relationId + memberReceiveAddressId + phone）
  ↓ FlashPromotionOrderServiceImpl
  ├─ ① 校验：登录、relation 属于当前有效场次（sms_flash_promotion_session.start_time/end_time）
  ├─ ② 限流：RSemaphore（每场次一个信号量），tryAcquire 失败 = "秒杀太火爆"
  ├─ ③ 一人一单：Redis 原子计数（key: seckill:limit:{relationId}:{memberId}）+1 预占，> flash_promotion_limit 失败
  ├─ ④ 扣秒杀额度：key seckill:stock:{relationId}，懒加载（setIfAbsent 写入 flash_promotion_count），原子减 1，不足失败
  ├─ ⑤ 落库：oms_order（order_type=1、秒杀价）+ oms_order_item + lockStock 锁 SKU 真实库存
  ├─ ⑥ 延时关单：复用现有 RabbitMQ TTL+DLX 链路，秒杀订单用 flashOrderOvertime
  └─ 任何一步失败 → 回滚 Redis 计数/库存/信号量
```

## 关键决策

| 决策点 | 方案 |
|---|---|
| 库存模型 | 秒杀专属额度（relation.flash_promotion_count 预热 Redis），与 pms_sku_stock 真实库存分离；下单成功后仍锁 SKU 库存 |
| 预热时机 | 懒加载：下单时 Redis 无 key 则 setIfAbsent 写入 flash_promotion_count |
| 防超卖 | 三层：RSemaphore 挡流量 → Redis 原子扣额度 → lockSkuStock 条件 UPDATE DB 兜底 |
| 一人一单 | Redis 原子 +1 预占，失败回滚 -1，成功后保留防重复下单 |
| 订单复用 | order_type=1 标记；支付/取消/超时关单全复用现有链路（含死信增强） |
| 超时改造 | sendDelayMessageCancelOrder 按 orderType 选 flashOrderOvertime / normalOrderOvertime |
| Redis 方案 | 引入 redisson-spring-boot-starter（root pom 已有 redisson.version=3.52.0） |

## 涉及文件

新增：
- mall-portal/controller/FlashPromotionOrderController.java
- mall-portal/service/FlashPromotionOrderService.java + impl/FlashPromotionOrderServiceImpl.java
- mall-portal/domain/FlashPromotionOrderParam.java
- mall-portal/dao/FlashPromotionDao.java + resources/dao/FlashPromotionDao.xml

修改：
- mall-portal/pom.xml：加 redisson-spring-boot-starter（${redisson.version}）
- OmsPortalOrderServiceImpl.sendDelayMessageCancelOrder：按 orderType 选超时时间

## 测试

1. curl 覆盖各分支：未登录 / 场次外 / 超限购 / 库存不足 / 成功下单
2. 并发压测：10 件秒杀额度、100 并发，验证 success=10、Redis 库存归 0、DB 锁库存一致、无超卖
3. 秒杀订单超时关单：临时调小 flashOrderOvertime，验证延时关单 + 库存释放
