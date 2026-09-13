package com.macro.mall.portal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务线程池：CompletableFuture异步编排专用
 * 不用ForkJoinPool.commonPool：它是全局共享池（核数-1个线程），
 * 业务慢查询会拖垮所有并行任务；独立池互不干扰，且线程名可识别（日志验证并行用）
 */
@Configuration
public class BusinessThreadPoolConfig {
    @Bean("businessExecutor")
    public ExecutorService businessExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "businessPool-" + seq.getAndIncrement());
                t.setDaemon(false); // 非守护线程：任务必须跑完
                return t;
            }
        };

        return new ThreadPoolExecutor(
                8,                                 // 核心线程数：常驻8个
                16,                                // 最大线程数：忙时扩到16
                60, TimeUnit.SECONDS,              // 空闲60秒回收超额线程
                new ArrayBlockingQueue<>(100),     // 队列容量：排满100后才继续开线程
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列也满：调用线程自己跑，任务不丢弃
        );
    }
}
