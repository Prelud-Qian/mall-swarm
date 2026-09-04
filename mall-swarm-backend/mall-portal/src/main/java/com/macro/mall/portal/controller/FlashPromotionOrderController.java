package com.macro.mall.portal.controller;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.portal.domain.FlashPromotionOrderParam;
import com.macro.mall.portal.service.FlashPromotionOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 秒杀订单Controller
 */
@Tag(name = "FlashPromotionOrderController", description = "秒杀订单管理")
@RestController
@RequestMapping("/flashPromotion")
public class FlashPromotionOrderController {
    @Autowired
    private FlashPromotionOrderService flashPromotionOrderService;

    @Operation(summary = "秒杀下单")
    @RequestMapping(value = "/order/generate", method = RequestMethod.POST)
    public CommonResult<Map<String, Object>> generateFlashPromotionOrder(@RequestBody FlashPromotionOrderParam param) {
        return CommonResult.success(flashPromotionOrderService.generateFlashPromotionOrder(param));
    }
}