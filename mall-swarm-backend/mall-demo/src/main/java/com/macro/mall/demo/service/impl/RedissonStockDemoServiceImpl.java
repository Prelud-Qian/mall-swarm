package com.macro.mall.demo.service.impl;

import com.macro.mall.demo.service.RedissonStockDemoService;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.PmsSkuStock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.macro.mall.demo.dao.SkuStockDao;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Redisson分布式锁——扣库存业务演示Service实现类
 */
@Service
public class RedissonStockDemoServiceImpl implements RedissonStockDemoService {

    // 放大竞态窗口：读库存和写库存之间停20毫秒，让"读到旧值"更容易发生
    private static final long SLEEP_MILLIS = 20;

    // 乐观锁冲突重试上限
    private static final int MAX_RETRY = 3;

    @Autowired
    private PmsSkuStockMapper skuStockMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private SkuStockDao skuStockDao;

    @Override
    public StockDeductResult deductStockWithoutLock(Long skuId, int count) {
        PmsSkuStock init = skuStockMapper.selectByPrimaryKey(skuId);
        StockDeductResult result = new StockDeductResult();
        result.setBeforeStock(init.getStock());
        // 多线程并发统计，必须用原子类：普通 int++ 是"读-改-写"三步，本身就会丢更新
        AtomicInteger success = new AtomicInteger();
        AtomicInteger noStock = new AtomicInteger();

        runConcurrent(count, () -> {
            // 一次"下单" = 读库存 -> 判断 -> 写库存 三步，全程无锁保护
            PmsSkuStock skuStock = skuStockMapper.selectByPrimaryKey(skuId);
            if (skuStock.getStock() <= 0){
                noStock.incrementAndGet();
                return;
            }
            sleepQuietly(SLEEP_MILLIS);
            skuStock.setStock(skuStock.getStock() - 1);
            skuStockMapper.updateByPrimaryKey(skuStock);
            success.incrementAndGet();
        });

        result.setAfterStock(skuStockMapper.selectByPrimaryKey(skuId).getStock());
        result.setSuccessCount(success.get());
        result.setNoStockCount(noStock.get());
        result.setBusyCount(0); // 无锁版没有"拿不到锁"这回事

        return result;
    }

    @Override
    public StockDeductResult deductStockWithLock(Long skuId, int count) {
        PmsSkuStock init = skuStockMapper.selectByPrimaryKey(skuId);
        StockDeductResult result = new StockDeductResult();
        result.setBeforeStock(init.getStock());
        AtomicInteger success = new AtomicInteger();
        AtomicInteger noStock = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();

        // 锁key按资源粒度：每个sku一把锁，不同sku互不干扰
        RLock lock = redissonClient.getLock("lock:stock:" + skuId);

        /**
         * 拿分布式锁 → 循环重试扣库存 → 扣成功就结束，扣失败就判断是没货还是冲突 → 冲突就等一会再试，没货就结束
         */
        runConcurrent(count, () -> {
            boolean locked;
            try{
                // 第1个参数：最多等锁3秒，拿不到就放弃，不无限阻塞
                // 第2个参数：最多持锁10秒，到点自动释放，防死锁
                locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!locked){
                // locked == false 说明 3 秒内没拿到锁
                //计数器 busy 原子自增，记录一次"系统繁忙"
                busy.incrementAndGet();
                //return 结束当前线程的任务，不执行扣库存
                return;
            }
            // 只有 locked == true 的线程才会进入这个 try 块
            try {
                // 分布式锁 + 乐观锁双保险
                for (int i = 0; i < MAX_RETRY; i++){
                    // 1. 执行原子扣减
                    if (skuStockDao.deductStock(skuId) > 0){
                        success.incrementAndGet();
                        sleepQuietly(SLEEP_MILLIS); // 模拟下单其余业务耗时，让排队线程能观察到锁等待
                        return;
                    }
                    // 2. 扣减失败，查询当前库存
                    Integer current = skuStockMapper.selectByPrimaryKey(skuId).getStock();
                    if (current == null || current <= 0) {
                        noStock.incrementAndGet();
                        return;
                    }
                    /**
                     * UPDATE 那一刻 stock=0 条件不成立 → 0 行；
                     * 重读前别人又改了库存 (别的地方恰好执行了 "补货" 语句 添加了库存数量)，
                     * 读到了新值 > 0 → 判定"有并发写者"→ 重试。
                     */
                    // 锁内发生的"冲突重试"在 demo 自己的线程之间根本不可能发生。冲突只能来自锁外写者（resetStock、真实下单链路），这也是"这个分支几乎不触发"的原因。
                    // 3. 库存 > 0，说明是乐观锁冲突，随机退避后重试
                    sleepQuietly(ThreadLocalRandom.current().nextLong(1, 10)); // 冲突随机退避后重试
                }
                busy.incrementAndGet(); // 重试耗尽仍没扣成（仅极端冲突才会走到），按繁忙处理
            } finally {
                // finally 确保锁被释放，但释放前检查锁是否还被当前线程持有
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });

        result.setAfterStock(skuStockMapper.selectByPrimaryKey(skuId).getStock());
        result.setSuccessCount(success.get());
        result.setBusyCount(busy.get());
        result.setNoStockCount(noStock.get());
        return result;
    }

    @Override
    public void resetStock(Long skuId, int stock) {
        PmsSkuStock skuStock = new PmsSkuStock();
        skuStock.setId(skuId);
        skuStock.setStock(stock);
        skuStockMapper.updateByPrimaryKeySelective(skuStock); // 只更新非null字段
    }


    /**
     * 开count个线程同时执行task，全部跑完后返回
     * （和 RedissonDemoServiceImpl 里的 runConcurrent 同款，直接照抄即可）
     */
    private void runConcurrent(int count, Runnable task) {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
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
