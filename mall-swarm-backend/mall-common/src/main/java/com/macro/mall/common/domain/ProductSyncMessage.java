package com.macro.mall.common.domain;

import lombok.Data;

/**
 * 商品上下架同步消息（admin发出 -> search消费）
 */
@Data
public class ProductSyncMessage {
    private Long productId;
    private Integer publishStatus; // 1->上架；0->下架
}
