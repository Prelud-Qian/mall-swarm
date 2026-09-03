package com.macro.mall.demo.dao;

import org.apache.ibatis.annotations.Param;

/**
 * SKU库存自定义Dao
 */
public interface SkuStockDao {
    /**
     * 原子扣减库存：stock=stock-1在DB内完成，库存不足时更新0行（乐观锁）
     */
    int deductStock(@Param("skuId") Long skuId);

}
