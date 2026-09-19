package com.macro.mall.portal.controller;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.PmsBrandMapper;
import com.macro.mall.model.PmsBrand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seata分布式事务演示接口（portal作为全局事务的参与者RM）
 */
@Tag(name = "SeataDemoController", description = "分布式事务演示")
@RestController
public class SeataDemoController {
    @Autowired
    private PmsBrandMapper brandMapper;

    /**
     * 演示写接口：写品牌，success=false时故意抛异常（触发全局回滚）
     */
    @Operation(summary = "分布式事务参与者演示写接口")
    @PostMapping("/brand/txDemo")
    public CommonResult<Void> txBrand(@RequestParam(defaultValue = "true") boolean success) {
        PmsBrand brand = new PmsBrand();
        brand.setName("seata-portal-" + System.currentTimeMillis());
        brandMapper.insertSelective(brand);
        if (!success) {
            Asserts.fail("演示远程失败，触发全局回滚");
        }
        return CommonResult.success(null);
    }
}
