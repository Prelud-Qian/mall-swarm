package com.macro.mall.dto;

import lombok.Data;

import java.util.Date;

/**
 * 本地消息表实体（可靠消息投递的账本记录）
 */
@Data
public class MallLocalMessage {
    private Long id;
    private String exchange;     // 交换机
    private String routingKey;   // 路由键
    private String payload;      // 消息体JSON
    private Integer status;      // 0-待发送 1-已发送
    private Integer retryCount;  // 已重试次数
    private Date createTime;
    private Date updateTime;
}
