package com.macro.mall.portal.config;

import com.macro.mall.portal.domain.QueueEnum;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;


/**
 * 消息队列相关配置
 * Created by macro on 2018/9/14.
 */
@Configuration
public class RabbitMqConfig {

    /**
     * 订单消息实际消费队列所绑定的交换机
     */
    @Bean
    DirectExchange orderDirect() {
        return (DirectExchange) ExchangeBuilder
                .directExchange(QueueEnum.QUEUE_ORDER_CANCEL.getExchange())
                .durable(true)
                .build();
    }

    /**
     * 订单延迟队列队列所绑定的交换机
     */
    @Bean
    DirectExchange orderTtlDirect() {
        return (DirectExchange) ExchangeBuilder
                .directExchange(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getExchange())
                .durable(true)
                .build();
    }

    /**
     * 订单实际消费队列
     */
    @Bean
    public Queue orderQueue() {
        return new Queue(QueueEnum.QUEUE_ORDER_CANCEL.getName());
    }

    /**
     * 订单延迟队列（死信队列）
     */
    @Bean
    public Queue orderTtlQueue() {
        return QueueBuilder
                .durable(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getName())
                .withArgument("x-dead-letter-exchange", QueueEnum.QUEUE_ORDER_CANCEL.getExchange())//到期后转发的交换机
                .withArgument("x-dead-letter-routing-key", QueueEnum.QUEUE_ORDER_CANCEL.getRouteKey())//到期后转发的路由键
                .build();
    }

    /**
     * 将订单队列绑定到交换机
     */
    @Bean
    Binding orderBinding(DirectExchange orderDirect,Queue orderQueue){
        return BindingBuilder
                .bind(orderQueue)
                .to(orderDirect)
                .with(QueueEnum.QUEUE_ORDER_CANCEL.getRouteKey());
    }

    /**
     * 将订单延迟队列绑定到交换机
     */
    @Bean
    Binding orderTtlBinding(DirectExchange orderTtlDirect,Queue orderTtlQueue){
        return BindingBuilder
                .bind(orderTtlQueue)
                .to(orderTtlDirect)
                .with(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getRouteKey());
    }

    /**
     * 死信队列交换机
     */
    @Bean
    DirectExchange orderCancelDlqDirect(){
        return (DirectExchange) ExchangeBuilder
                .directExchange(QueueEnum.QUEUE_ORDER_CANCEL_DLQ.getExchange())
                .durable(true)
                .build();
    }

    /**
     * 死信队列：重试耗尽后的消息落此处
     * 死信队列不需要消费者，消息落进去等着人工查看
     */
    @Bean
    public Queue orderCancelDlqQueue(){
        return new Queue(QueueEnum.QUEUE_ORDER_CANCEL_DLQ.getName());
    }

    /**
     * 将死信队列绑定到死信交换机
     */
    @Bean
    Binding orderCancelDlqBinding(DirectExchange orderCancelDlqDirect, Queue orderCancelDlqQueue) {
        return BindingBuilder
                .bind(orderCancelDlqQueue)
                .to(orderCancelDlqDirect)
                .with(QueueEnum.QUEUE_ORDER_CANCEL_DLQ.getRouteKey());
    }

    /**
     * 自定义监听器容器工厂：消费失败自动重试3次（间隔2秒、4秒），
     * 重试耗尽的消息由 RepublishMessageRecoverer 转发到死信队列，
     * 从根源上杜绝毒消息无限 requeue
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, RabbitTemplate rabbitTemplate) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // 关闭默认的"消费失败无限重新入队"，失败交由下面的重试拦截器处理
        factory.setDefaultRequeueRejected(false);
        RetryOperationsInterceptor retryInterceptor = RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(2000, 2, 5000)
                .recoverer(new RepublishMessageRecoverer(
                        rabbitTemplate,
                        QueueEnum.QUEUE_ORDER_CANCEL_DLQ.getExchange(),
                        QueueEnum.QUEUE_ORDER_CANCEL_DLQ.getRouteKey()
                ))
                .build();
        // 把重试拦截器挂到容器的 advice 链上，对 CancelOrderReceiver 生效
        factory.setAdviceChain(retryInterceptor);

        return factory;
    }

}
