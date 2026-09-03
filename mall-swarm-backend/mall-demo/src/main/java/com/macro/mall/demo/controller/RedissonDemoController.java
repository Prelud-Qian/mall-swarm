package com.macro.mall.demo.controller;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.demo.service.RedissonDemoService;
import com.macro.mall.demo.service.RedissonStockDemoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Redisson分布式锁演示Controller
 */
@Tag(name = "RedissonDemoController", description = "Redisson分布式锁演示接口")
@Controller
@RequestMapping("/redisson")
public class RedissonDemoController {
    @Autowired
    private RedissonDemoService redissonDemoService;

    @Autowired
    private RedissonStockDemoService redissonStockDemoService;

    @Operation(summary = "不加锁并发计数（结果会小于20）")
    @RequestMapping(value = "/noLock", method = RequestMethod.GET) // 等价写法 @GetMapping("/noLock")
    @ResponseBody // 方法的返回值直接写入 HTTP 响应体，而不是解析为视图名称。
    public CommonResult<Integer> noLock(){
        int result = redissonDemoService.incrementWithoutLock();
        return CommonResult.success(result, "预期20，实际：" + result);
    }


    @Operation(summary = "加锁并发计数（结果等于20）")
    @RequestMapping(value = "/withLock", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<Integer> withLock() {
        int result = redissonDemoService.incrementWithLock();
        return CommonResult.success(result, "预期20，实际：" + result);
    }

    @Operation(summary = "不加锁并发扣库存（会超卖）")
    @RequestMapping(value = "/stockDeductWithoutLock", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<RedissonStockDemoService.StockDeductResult> stockDeductWithoutLock(
            @RequestParam(defaultValue = "99") Long skuId,
            @RequestParam(defaultValue = "20") int count) {
        return CommonResult.success(redissonStockDemoService.deductStockWithoutLock(skuId, count));
    }

    @Operation(summary = "加锁并发扣库存（精确扣减）")
    @RequestMapping(value = "/stockDeductWithLock", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<RedissonStockDemoService.StockDeductResult> stockDeductWithLock(
            @RequestParam(defaultValue = "99") Long skuId,
            @RequestParam(defaultValue = "20") int count) {
        return CommonResult.success(redissonStockDemoService.deductStockWithLock(skuId, count));
    }

    @Operation(summary = "重置库存")
    @RequestMapping(value = "/stockReset", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<Void> stockReset(@RequestParam(defaultValue = "99") Long skuId,
                                         @RequestParam(defaultValue = "100") int stock) {
        redissonStockDemoService.resetStock(skuId, stock);
        return CommonResult.success(null);
    }
}
