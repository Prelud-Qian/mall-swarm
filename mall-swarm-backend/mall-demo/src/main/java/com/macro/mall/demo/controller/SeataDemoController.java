package com.macro.mall.demo.controller;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.demo.service.impl.SeataDemoServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seata分布式事务演示接口
 */
@Tag(name = "SeataDemoController", description = "分布式事务演示")
@RestController
@RequestMapping("/tx")
public class SeataDemoController {

    @Autowired
    private SeataDemoServiceImpl seataDemoService;

    @Operation(summary = "分布式事务演示：success=false时全局回滚")
    @PostMapping("/brand")
    public CommonResult<String> txBrand(@RequestParam(defaultValue = "true") boolean success) {
        return CommonResult.success(seataDemoService.txBrand(success));
    }
}
