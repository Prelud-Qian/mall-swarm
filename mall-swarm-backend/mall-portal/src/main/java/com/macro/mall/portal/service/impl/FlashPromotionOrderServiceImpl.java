package com.macro.mall.portal.service.impl;

import com.alibaba.nacos.common.utils.CollectionUtils;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.*;
import com.macro.mall.model.*;
import com.macro.mall.portal.dao.PortalOrderDao;
import com.macro.mall.portal.domain.FlashPromotionOrderParam;
import com.macro.mall.portal.service.FlashPromotionOrderService;
import com.macro.mall.portal.service.OmsPortalOrderService;
import com.macro.mall.portal.service.UmsMemberReceiveAddressService;
import com.macro.mall.portal.service.UmsMemberService;
import com.macro.mall.portal.util.DateUtil;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class FlashPromotionOrderServiceImpl implements FlashPromotionOrderService {

    // 每场次的并发信号量数量：100 个线程同时进来
    private static final int SEMAPHORE_PERMITS = 100;

    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private SmsFlashPromotionProductRelationMapper relationMapper;
    @Autowired
    private SmsFlashPromotionSessionMapper sessionMapper;
    @Autowired
    private PmsProductMapper productMapper;
    @Autowired
    private PmsSkuStockMapper skuStockMapper;
    @Autowired
    private OmsPortalOrderService portalOrderService;
    @Autowired
    private PortalOrderDao portalOrderDao;
    @Autowired
    private OmsOrderMapper orderMapper;
    @Autowired
    private OmsOrderItemMapper orderItemMapper;
    @Autowired
    private UmsMemberService memberService;
    @Autowired
    private UmsMemberReceiveAddressService memberReceiveAddressService;

    private static final String DEDUCT_STOCK_LUA =
            "if redis.call('exists', KEYS[1]) == 0 then return -2 end " +
                    "local stock = tonumber(redis.call('get', KEYS[1])) " +
                    "if stock <= 0 then return -1 end " +
                    "redis.call('decr', KEYS[1]) " +
                    "return stock - 1";

    @Transactional
    @Override
    public Map<String, Object> generateFlashPromotionOrder(FlashPromotionOrderParam param) {
        // 1. 校验场次
        SmsFlashPromotionProductRelation relation = relationMapper.selectByPrimaryKey(param.getFlashPromotionRelationId());
        if (relation == null) {
            Asserts.fail("秒杀商品不存在");
        }
        SmsFlashPromotionSession session = sessionMapper.selectByPrimaryKey(relation.getFlashPromotionSessionId());
        // 场次时间是TIME类型（如8:00），只比时分秒：先把当前时间归一化到1970-01-01再比较
        Date currTime = DateUtil.getTime(new Date());
        if (session == null || currTime.before(session.getStartTime()) || currTime.after(session.getEndTime())) {
            Asserts.fail("不在秒杀场次时间内");
        }

        /**
         * 涉及共享资源（信号量、计数器、库存 Key、数据库记录）的操作，是串行化的（通过原子操作或锁）。
         * 涉及本地变量（订单对象、查询结果）的操作，是完全独立的，线程间互不干扰。
         */

        /**
         * 每个线程独立执行
         */
        UmsMember member = memberService.getCurrentMember();

        /**
         * 多线程共享
         */
        // 2. 限流：每个场次一个信号量
        // 使用 Redisson 的分布式信号量（Semaphore）来实现秒杀限流
        RSemaphore semaphore = redissonClient.getSemaphore("seckill:semaphore:" + session.getId());
        // 将信号量的许可证数量设置为 SEMAPHORE_PERMITS（即库存总数）
        // trySetPermits() 的特性：只在信号量未初始化时设置，如果已经存在则忽略。
        // 这保证了库存数量只在活动开始时设置一次，不会因为并发请求而重复覆盖。
        semaphore.trySetPermits(SEMAPHORE_PERMITS);
        // 通过 Redis 分布式信号量，限制同时进入秒杀流程的请求数量（许可证数 = 库存数），
        // 当许可证用完时，后续请求直接返回“秒杀太火爆”，达到限流效果。
        boolean acquired;
        try {
            /**
             * 尝试获取 1 个许可证。
             * 如果有可用许可证（剩余库存 > 0），立即获取，返回 true。
             * 如果没有可用许可证（库存 = 0），最多等待 3 秒，期间如果有其他线程释放了许可证（比如回滚），则获取成功；否则超时返回 false。
             */
            acquired = semaphore.tryAcquire(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            // 限流触发点：如果 3 秒内没拿到许可证，直接抛出异常，返回“秒杀太火爆”。
            // 这相当于在 Redis 层拦截了超量的请求，避免大量请求涌入数据库。
            Asserts.fail("秒杀太火爆，请稍后再试");
        }


        // 获取成功  许可证 -1   执行下单逻辑（信号量继续持有）
        boolean stockDeducted = false;
        boolean limitIncremented = false;
        try {
            // 3. 一人一单
            // 从 Redisson 客户端获取一个分布式原子长整型（RAtomicLong）计数器，用于限流或计数控制。
            RAtomicLong limitCounter = redissonClient.getAtomicLong("seckill:limit:" + relation.getId() + ":" + member.getId());
            long boughtCount = limitCounter.incrementAndGet();
            limitIncremented = true;
            if (boughtCount > relation.getFlashPromotionLimit()) {
                Asserts.fail("每人限购" + relation.getFlashPromotionLimit() + "件");
            }
            // 4. 扣秒杀额度：懒加载预热（SETNX，只有第一个请求写入配置的秒杀数量），再用Lua原子扣减
            String stockKey = "seckill:stock:" + relation.getId();
            RBucket<Integer> stockBucket = redissonClient.getBucket(stockKey, StringCodec.INSTANCE);
            stockBucket.trySet(relation.getFlashPromotionCount());
            Long left = redissonClient.getScript().eval(
                    RScript.Mode.READ_WRITE, DEDUCT_STOCK_LUA, RScript.ReturnType.INTEGER,
                    Collections.singletonList(stockKey));
            if (left < 0) {
                Asserts.fail("秒杀商品已抢完");
            }
            stockDeducted = true;
            // 5. 生成订单 + 锁库存 + 发延时消息
            return createFlashOrder(relation, member, param);
        } catch (Exception e) {
            // 回滚：库存返还、限购计数返还
            if (stockDeducted) {
                redissonClient.getScript().eval(RScript.Mode.READ_WRITE,
                        "redis.call('incr', KEYS[1])", RScript.ReturnType.INTEGER,
                        Collections.singletonList("seckill:stock:" + relation.getId()));
            }
            if (limitIncremented) {
                redissonClient.getAtomicLong("seckill:limit:" + relation.getId() + ":" + member.getId()).decrementAndGet();
            }
            throw e;
        } finally {
            // 信号量释放
            // 信号量一旦被释放（release()），许可证的数量就会立刻增加回去。
            semaphore.release();
        }

    }

    private Map<String, Object> createFlashOrder(SmsFlashPromotionProductRelation relation, UmsMember member, FlashPromotionOrderParam param) {
        // 秒杀商品信息
        PmsProduct product = productMapper.selectByPrimaryKey(relation.getProductId());
        // 简化处理：秒杀按商品粒度，取该商品第一个SKU（正式版应按SKU维度做秒杀）
        PmsSkuStockExample skuExample = new PmsSkuStockExample();
        skuExample.createCriteria().andProductIdEqualTo(product.getId());
        List<PmsSkuStock> skuStockList = skuStockMapper.selectByExample(skuExample);
        if (CollectionUtils.isEmpty(skuStockList)) {
            Asserts.fail("商品库存不存在");
        }
        PmsSkuStock skuStock = skuStockList.get(0);

        // 生成订单
        OmsOrder order = new OmsOrder();
        order.setOrderSn(generateOrderSn(member));
        order.setMemberId(member.getId());
        order.setMemberUsername(param.getPhone());
        order.setTotalAmount(relation.getFlashPromotionPrice());
        order.setPayAmount(relation.getFlashPromotionPrice());
        order.setOrderType(1); // 1->秒杀订单
        order.setStatus(0); // 待付款
        order.setDeleteStatus(0);
        order.setConfirmStatus(0);
        order.setSourceType(1);
        order.setPayType(0);
        // 收货人信息：oms_order的receiver_*字段非空，需从收货地址查出来
        UmsMemberReceiveAddress address = memberReceiveAddressService.getItem(param.getMemberReceiveAddressId());
        order.setReceiverName(address.getName());
        order.setReceiverPhone(address.getPhoneNumber());
        order.setReceiverPostCode(address.getPostCode());
        order.setReceiverProvince(address.getProvince());
        order.setReceiverCity(address.getCity());
        order.setReceiverRegion(address.getRegion());
        order.setReceiverDetailAddress(address.getDetailAddress());
        order.setCreateTime(new Date());
        orderMapper.insert(order);

        // 生成订单商品明细
        OmsOrderItem orderItem = new OmsOrderItem();
        orderItem.setOrderId(order.getId());
        orderItem.setOrderSn(order.getOrderSn());
        orderItem.setProductId(product.getId());
        orderItem.setProductName(product.getName());
        orderItem.setProductPic(product.getPic());
        orderItem.setProductPrice(relation.getFlashPromotionPrice());
        orderItem.setProductQuantity(1);
        orderItem.setProductSkuId(skuStock.getId());
        orderItem.setProductSkuCode(skuStock.getSkuCode());
        orderItemMapper.insert(orderItem);

        // 锁SKU真实库存（条件UPDATE，0行=不足），失败会抛异常走外层回滚
        int count = portalOrderDao.lockSkuStock(skuStock.getId(), 1);
        if (count == 0){
            Asserts.fail("库存不足，无法下单");
        }

        // 发延时关单消息（第6步会把秒杀订单的超时改为 flashOrderOvertime）
        portalOrderService.sendDelayMessageCancelOrder(order.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("order", order);
        result.put("orderItemList", Collections.singletonList(orderItem));
        return result;
    }

    // 订单号：秒杀用 F + 时间戳 + 随机数（普通订单用Redis自增，这里简化）
    private String generateOrderSn(UmsMember member) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        StringBuilder sb = new StringBuilder("F");
        sb.append(sdf.format(new Date()));
        sb.append(member.getId());
        return sb.toString();
    }
}
