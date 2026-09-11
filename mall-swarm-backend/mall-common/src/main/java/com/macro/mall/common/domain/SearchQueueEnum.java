package com.macro.mall.common.domain;

import lombok.Getter;

/**
 * 商品索引同步队列枚举
 */
@Getter
public enum SearchQueueEnum {
    PRODUCT_SYNC("mall.search.direct", "mall.search.product", "mall.search.product");

    private String exchange;
    private String name;
    private String routeKey;

    SearchQueueEnum(String exchange, String name, String routeKey) {
        this.exchange = exchange;
        this.name = name;
        this.routeKey = routeKey;
    }
}
