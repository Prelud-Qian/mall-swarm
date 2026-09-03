package com.macro.mall.demo.service.impl;

import com.macro.mall.demo.service.RedissonDemoService;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redisson分布式锁演示Service实现类
 */
@Service
public class RedissonDemoServiceImpl implements RedissonDemoService {

    // 并发测试的线程数量，即启动 20 个线程同时执行
    private static final int THREAD_COUNT = 20;

    // 核心客户端对象，通过它可以获取分布式锁、原子计数器等
    @Autowired
    private RedissonClient redissonClient;

    /**
     * 不加锁的计数器自增测试，用于演示并发问题
     * @return
     */
    @Override
    public int incrementWithoutLock() {
        // 通过 Redis 创建一个分布式原子长整型计数器
        RAtomicLong counter = redissonClient.getAtomicLong("demo:redisson:counter");
        counter.set(0);
        runConcurrent(() -> {
            //读-改-写，中间sleep放大竞态窗口
            long current = counter.get();
            sleepQuietly(20);
            counter.set(current + 1);
        });
        return (int) counter.get();
    }

    @Override
    public int incrementWithLock() {
        RAtomicLong counter = redissonClient.getAtomicLong("demo:redisson:counter");
        counter.set(0);
        // 从Redisson客户端获取一个分布式锁对象，锁的key为 "demo:redisson:lock"
        RLock lock = redissonClient.getLock("demo:redisson:lock");
        runConcurrent(() -> {
            // 获取分布式锁，阻塞直到成功
            lock.lock();
            try{
                // 同一段读-改-写，但被锁保护
                long current = counter.get();
                sleepQuietly(20);
                counter.set(current + 1);
            } finally {
                // 释放分布式锁
                lock.unlock();
            }
        });
        return (int) counter.get();
    }

    /**
     * 开20个线程同时执行任务，全部结束后返回
     */
    private void runConcurrent(Runnable task){
        // 创建一个固定大小的线程池，线程数为 THREAD_COUNT（假设为20）
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        // 创建倒计数锁存器，初始计数 = 20
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            pool.execute(() -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 关闭线程池，拒绝新任务提交，但已提交的任务会继续执行
        pool.shutdown();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
