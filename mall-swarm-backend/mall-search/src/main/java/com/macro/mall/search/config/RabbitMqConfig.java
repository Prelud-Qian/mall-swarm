package com.macro.mall.search.config;

import com.macro.mall.common.domain.SearchQueueEnum;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
