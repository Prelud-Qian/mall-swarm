package com.macro.mall.portal.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 秒杀下单参数
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class FlashPromotionOrderParam {
    @Schema(title = "秒杀商品关联ID")
    private Long flashPromotionRelationId;
    @Schema(title = "收货地址ID")
    private Long memberReceiveAddressId;
    @Schema(title = "手机号")
    private String phone;
}
