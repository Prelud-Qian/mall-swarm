package com.macro.mall.component;

import com.macro.mall.service.LocalMessageService;
import org.mybatis.logging.Logger;
import org.mybatis.logging.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 本地消息补发定时任务：每30秒扫描待发送记录重发（MQ故障自愈）
 */
@Component
public class LocalMessageResendTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalMessageResendTask.class);

    @Autowired
    private LocalMessageService localMessageService;

    /**
     * fixedDelay：上一次执行完成后再等30秒（不会任务堆积）
     */
    @Scheduled(fixedDelay = 30000)
    public void resend() {
        localMessageService.sendPending();
    }
}
