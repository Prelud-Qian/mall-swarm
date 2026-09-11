package com.macro.mall.search.component;

import com.macro.mall.common.domain.ProductSyncMessage;
import com.macro.mall.search.service.EsProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
    public void handle(ProductSyncMessage message) {
        if (message.getPublishStatus() == 1) {
            // 上架：从DB查该商品并写入ES索引
            esProductService.create(message.getProductId());
            LOGGER.info("商品{}上架，索引已创建", message.getProductId());
        } else {
            // 下架：从ES删除
            esProductService.delete(message.getProductId());
            LOGGER.info("商品{}下架，索引已删除", message.getProductId());
        }
    }
}
