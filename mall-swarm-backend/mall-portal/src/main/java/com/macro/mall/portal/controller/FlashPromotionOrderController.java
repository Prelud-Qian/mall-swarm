package com.macro.mall.portal.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.exception.ApiException;
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
    @SentinelResource(value = "flashOrder",
            blockHandler = "flashOrderBlockHandler",
            fallback = "flashOrderFallback")
    public CommonResult<Map<String, Object>> generateFlashPromotionOrder(@RequestBody FlashPromotionOrderParam param) {
        return CommonResult.success(flashPromotionOrderService.generateFlashPromotionOrder(param));
    }

    /**
     * 拦截出口分流：熔断(DegradeException)与限流给不同提示
     */
    public CommonResult<Map<String, Object>> flashOrderBlockHandler(FlashPromotionOrderParam param, BlockException e) {
        if (e instanceof DegradeException) {
            return CommonResult.failed("秒杀服务正在恢复，请稍后再试");
        }
        return CommonResult.failed("秒杀太火爆，请稍后再试");
    }

    /**
     * 秒杀业务异常兜底：业务规则异常（限购/场次外）透传原消息；
     * 系统异常才返回统一兜底文案
     */
    public CommonResult<Map<String, Object>> flashOrderFallback(FlashPromotionOrderParam param, Throwable e) {
        if (e instanceof ApiException) {
            return CommonResult.failed(e.getMessage());
        }
        return CommonResult.failed("秒杀服务暂时不可用，请稍后再试");
    }

}