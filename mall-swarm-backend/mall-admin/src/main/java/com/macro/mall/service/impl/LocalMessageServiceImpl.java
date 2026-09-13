package com.macro.mall.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.dao.LocalMessageDao;
import com.macro.mall.dto.MallLocalMessage;
import com.macro.mall.service.LocalMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 本地消息服务实现：可靠消息投递
 * 写路径：saveMessage（事务内落库）→ afterCommit发送
 * 补路径：定时任务调sendPending，扫描未发送记录重发
 */
@Service
public class LocalMessageServiceImpl implements LocalMessageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalMessageServiceImpl.class);

    @Autowired
    private LocalMessageDao localMessageDao;
    @Autowired
    private RabbitTemplate amqpTemplate; // RabbitTemplate才有带CorrelationData的convertAndSend重载（AmqpTemplate接口没有）
    @Autowired
    private ObjectMapper objectMapper; // Spring自动配置的Jackson，与MQ消息转换器同源

    @Override
    public void saveMessage(String exchange, String routingKey, Object payload) {
        MallLocalMessage message = new MallLocalMessage();
        message.setExchange(exchange);
        message.setRoutingKey(routingKey);
        try{
            // 对象→JSON字符串落库：消息内容是完整快照，不依赖后续对象状态
            message.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("消息序列化失败", e);
        }
        localMessageDao.insert(message);
    }

    @Override
    public void sendPending() {
        List<MallLocalMessage> pendingList = localMessageDao.listPending();
        for (MallLocalMessage message : pendingList) {
            try {
                // JSON还原成Map再发送（发送端Jackson转换器序列化为JSON体，
                // 消费端按ProductSyncMessage反序列化，字段名对齐即可）
                Object payload = objectMapper.readValue(message.getPayload(), Object.class);
                amqpTemplate.convertAndSend(message.getExchange(), message.getRoutingKey(), payload,
                        new CorrelationData(String.valueOf(message.getId())));
                LOGGER.info("本地消息已提交，等待确认 id={}", message.getId());
            } catch (Exception e) {
                // 发送失败不抛出：重试次数+1，留给定时任务下次补发
                localMessageDao.increaseRetry(message.getId());
                LOGGER.warn("本地消息发送失败，等待补发 id={}", message.getId());
            }
        }
    }
}
