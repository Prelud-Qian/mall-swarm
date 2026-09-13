package com.macro.mall.config;

import com.macro.mall.common.domain.SearchQueueEnum;
import com.macro.mall.dao.LocalMessageDao;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商品索引同步消息队列配置（发送端）
 */
@Configuration
public class RabbitMqConfig {

    /**
     * JSON消息转换器：定义这个Bean后，RabbitTemplate会自动使用它收发JSON
     */
    @Bean
    public Jackson2JsonMessageConverter messageConverter(){
        return new Jackson2JsonMessageConverter();
    }

    // 被退回的消息ID集合：Broker对不可路由消息会【先退回、后确认】，
    // 用这个集合防止确认回调把退回的消息误标"已发送"
    private final Set<Long> returnedMessageIds = ConcurrentHashMap.newKeySet();


    @Bean
    DirectExchange searchDirect() {
        return (DirectExchange) ExchangeBuilder
                .directExchange(SearchQueueEnum.PRODUCT_SYNC.getExchange())
                .durable(true)
                .build();
    }

    @Bean
    public Queue searchProductQueue() {
        return new Queue(SearchQueueEnum.PRODUCT_SYNC.getName());
    }

    @Bean
    Binding searchProductBinding(DirectExchange searchDirect, Queue searchProductQueue) {
        return BindingBuilder
                .bind(searchProductQueue)
                .to(searchDirect)
                .with(SearchQueueEnum.PRODUCT_SYNC.getRouteKey());
    }

    /**
     * 可靠发送的RabbitTemplate：
     * mandatory=true——不可路由的消息触发ReturnsCallback（默认是静默丢弃！）
     * ConfirmCallback——ack=true到达交换机才markSent；ack=false增加重试计数
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, LocalMessageDao localMessageDao) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        template.setMandatory(true);
        // 到了交换机但路由不到任何队列（如exchange拼错）：记退回 + 重试计数
        template.setReturnsCallback(returned -> {
            String correlationId = returned.getMessage().getMessageProperties().getCorrelationId();
            if (correlationId != null) {
                Long messageId = Long.valueOf(correlationId);
                returnedMessageIds.add(messageId);
                localMessageDao.increaseRetry(messageId);
            }
        });
        // 确认回调：ack=true且【没有被退回】才markSent
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (correlationData == null) {
                return;
            }
            Long messageId = Long.valueOf(correlationData.getId());
            // Set.remove返回boolean：true=之前存在（被退回过，不标已发送）；false=没被退回，才markSent
            if (ack && !returnedMessageIds.remove(messageId)) {
                localMessageDao.markSent(messageId);
            }
            if (!ack) {
                localMessageDao.increaseRetry(messageId);
            }
        });

        return template;
    }

}
