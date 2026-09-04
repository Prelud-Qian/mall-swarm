package com.macro.mall.portal.service;

import com.macro.mall.portal.domain.FlashPromotionOrderParam;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 秒杀下单Service
 */
@Transactional
public interface FlashPromotionOrderService {
    /**
     * 秒杀下单：校验场次 -> 限流 -> 限购 -> 扣Redis库存 -> 生成订单
     *
     * @return order(订单) + orderItemList(订单商品明细)
     */
    Map<String, Object> generateFlashPromotionOrder(FlashPromotionOrderParam param);

}
