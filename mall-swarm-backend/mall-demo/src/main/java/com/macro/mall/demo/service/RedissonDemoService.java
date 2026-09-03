package com.macro.mall.demo.service;

/**
 * Redisson分布式锁演示Service
 */
public interface RedissonDemoService {
    /**
     * 不加锁的并发计数（会丢失更新）
     */
    int incrementWithoutLock();
    /**
     * 加Redisson锁的并发计数（结果正确）
     */
    int incrementWithLock();
}
