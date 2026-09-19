package com.macro.mall.demo.service.impl;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.demo.service.FeignPortalService;
import com.macro.mall.mapper.PmsBrandMapper;
import com.macro.mall.model.PmsBrand;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Seata分布式事务演示：demo服务作为全局事务发起方(TM)
 */
@Service
public class SeataDemoServiceImpl {
    @Autowired
    private PmsBrandMapper brandMapper;
    @Autowired
    private FeignPortalService feignPortalService;

    /**
     * 全局事务：本地写品牌 + 远程调portal写品牌
     * @param success true=两端都提交；false=portal抛异常，全局回滚（demo的本地写也撤销）
     */
    @GlobalTransactional
    public String txBrand(boolean success) {
        // 本地写（参与全局事务的RM之一）
        PmsBrand brand = new PmsBrand();
        brand.setName("seata-demo-" + System.currentTimeMillis());
        brandMapper.insertSelective(brand);
        // 远程写：调portal的演示接口
        // 关键：远程业务失败（HTTP 200但code!=200）必须手动感知并抛异常，
        // 否则全局事务不知道下游失败了，会照常提交
        CommonResult result = feignPortalService.txBrand(success);
        if (result.getCode() != 200) {
            throw new RuntimeException("远程调用失败：" + result.getMessage());
        }
        return "本地写+远程写均提交";
    }
}

/**
 * 没有 Seata 时：
 *   第1步 demo 写品牌 → demo 本地事务提交 ✅（数据落库了）
 *   第2步 portal 写品牌 → portal 抛异常 ❌（只回滚 portal 自己）
 *   结果：demo 的品牌记录【永远留在库里】——脏数据，没人管
 *
 * 有 Seata 时（@GlobalTransactional 生效）：
 *   第1步 demo 写品牌（先不真正提交，挂起等待全局裁决）
 *   第2步 portal 抛异常 → 通知 TC → TC 通知 demo："全局失败，回滚"
 *   结果：demo 的品牌记录被 undo_log 撤销——两端一起消失
 */