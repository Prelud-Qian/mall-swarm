package com.macro.mall.search.component;

import com.macro.mall.search.service.EsProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 商品上下架同步消费者：上架建索引、下架删索引
 */
@Component
@RabbitListener(queues = "mall.search.product")
public class ProductSyncReceiver {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProductSyncReceiver.class);

    @Autowired
    private EsProductService esProductService;

    @RabbitHandler
    public void handle(Map<String, Object> message) {
        // outbox消息契约：按JSON字段解析，不依赖具体类（跨服务解耦）
        Long productId = Long.valueOf(String.valueOf(message.get("productId")));
        Integer publishStatus = Integer.valueOf(String.valueOf(message.get("publishStatus")));
        if (publishStatus == 1) {
            // 上架：从DB查该商品并写入ES索引
            esProductService.create(productId);
            LOGGER.info("商品{}上架，索引已创建", productId);
        } else {
            // 下架：从ES删除
            esProductService.delete(productId);
            LOGGER.info("商品{}下架，索引已删除", productId);
        }
    }
}
