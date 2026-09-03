package com.macro.mall.demo.service;

import lombok.Data;

/**
 * Redisson分布式锁——扣库存业务演示Service
 */
public interface RedissonStockDemoService {
    /**
     * 不加锁的并发扣库存（会发生超卖）
     *
     * @param skuId 库存记录id
     * @param count 并发下单数
     */
    StockDeductResult deductStockWithoutLock(Long skuId, int count);

    /**
     * 加Redisson分布式锁 + DB乐观锁的并发扣库存
     */
    StockDeductResult deductStockWithLock(Long skuId, int count);

    /**
     * 重置库存，方便反复演示
     */
    void resetStock(Long skuId, int stock);

    /**
     * 扣库存结果统计
     */
    @Data
    class StockDeductResult {
        private int beforeStock;   // 并发开始前库存
        private int afterStock;    // 并发结束后库存
        private int successCount;  // 扣减成功次数
        private int busyCount;     // 拿不到锁，快速失败次数
        private int noStockCount;  // 库存不足，没扣成次数
    }
}
