package com.macro.mall.dao;

import com.macro.mall.dto.MallLocalMessage;
import feign.Param;

import java.util.List;

/**
 * 本地消息表DAO：可靠消息投递的落库/补发操作
 */
public interface LocalMessageDao {
    /**
     * 插入一条待发送消息（在业务事务内调用，与业务数据同事务）
     */
    int insert(MallLocalMessage message);

    /**
     * 查询待发送且重试未超限的消息（补发扫描）
     */
    List<MallLocalMessage> listPending();

    /**
     * 标记已发送
     */
    int markSent(@Param("id") Long id);

    /**
     * 重试次数+1（发送失败时调用）
     */
    int increaseRetry(@Param("id") Long id);
}
