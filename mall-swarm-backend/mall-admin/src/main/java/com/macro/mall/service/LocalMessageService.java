package com.macro.mall.service;

/**
 * 本地消息服务：可靠消息投递（落库、发送、补发）
 */
public interface LocalMessageService {
    /**
     * 保存待发送消息。必须在业务事务内调用——消息记录与业务数据同事务落库
     */
    void saveMessage(String exchange, String routingKey, Object payload);

    /**
     * 发送所有待发送消息：事务提交后立即发送 与 定时补发 共用此方法
     */
    void sendPending();
}
