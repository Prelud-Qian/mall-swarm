package com.macro.mall.portal.service;

import com.macro.mall.common.exception.ApiException;
import com.macro.mall.mapper.*;
import com.macro.mall.model.*;
import com.macro.mall.portal.dao.PortalOrderDao;
import com.macro.mall.portal.domain.FlashPromotionOrderParam;
import com.macro.mall.portal.service.impl.FlashPromotionOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 秒杀下单Service单元测试
 * <p>
 * 设计思想：全部依赖用Mock替身（不连Redis/MySQL），只验证"业务分支逻辑对不对"——
 * 什么条件下抛什么异常、回滚补偿是否按标记位精确执行、成功路径是否调齐下游。
 */
@ExtendWith(MockitoExtension.class)
// 宽松模式：setUp里为"全正常路径"预置的stub，部分用例走不到也算合法（默认严格模式会报UnnecessaryStubbing）
@MockitoSettings(strictness = Strictness.LENIENT)
public class FlashPromotionOrderServiceImplTest {

    @InjectMocks
    private FlashPromotionOrderServiceImpl flashPromotionOrderService;

    // ==================== Mock替身 ====================

    @Mock
    private SmsFlashPromotionProductRelationMapper relationMapper;
    @Mock
    private SmsFlashPromotionSessionMapper sessionMapper;
    @Mock
    private PmsProductMapper productMapper;
    @Mock
    private PmsSkuStockMapper skuStockMapper;
    @Mock
    private OmsOrderMapper orderMapper;
    @Mock
    private OmsOrderItemMapper orderItemMapper;
    @Mock
    private PortalOrderDao portalOrderDao;
    @Mock
    private OmsPortalOrderService portalOrderService;
    @Mock
    private UmsMemberService memberService;
    @Mock
    private UmsMemberReceiveAddressService memberReceiveAddressService;

    // Redisson对象链：每个对象都要mock，链式调用才能走通
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RSemaphore semaphore;
    @Mock
    private RAtomicLong limitCounter;
    @Mock
    private RBucket stockBucket; // 原始类型：getBucket返回RBucket<Object>，泛型精确声明反而对不上
    @Mock
    private RScript script;

    // ==================== 公共测试数据 ====================

    private SmsFlashPromotionProductRelation relation;
    private SmsFlashPromotionSession session;
    private UmsMember member;
    private FlashPromotionOrderParam param;

    /**
     * 每个用例执行前重置公共数据与默认Mock行为：
     * 默认全部走"正常路径"，各用例只需覆盖自己关心的那一个点
     */
    @BeforeEach
    public void setUp() throws InterruptedException { // tryAcquire声明了checked异常，mock时需处理
        // 场次窗口设为全天（1970-01-01 00:00~23:59），保证"当前时分秒"恒在窗口内
        session = new SmsFlashPromotionSession();
        session.setId(1L);
        session.setStartTime(new Date(0));
        session.setEndTime(new Date(23 * 3600 * 1000 + 59 * 60 * 1000 + 59 * 1000));

        // 秒杀关联：id=1、商品26、库存10、限购1、秒杀价3000
        relation = new SmsFlashPromotionProductRelation();
        relation.setId(1L);
        relation.setProductId(26L);
        relation.setFlashPromotionSessionId(1L);
        relation.setFlashPromotionCount(10);
        relation.setFlashPromotionLimit(1);
        relation.setFlashPromotionPrice(new BigDecimal("3000.00"));

        member = new UmsMember();
        member.setId(1L);

        param = new FlashPromotionOrderParam();
        param.setFlashPromotionRelationId(1L);
        param.setMemberReceiveAddressId(1L);
        param.setPhone("18061581849");

        // 默认Mock行为：按业务key返回对应的mock对象（各用例按需覆盖）
        when(relationMapper.selectByPrimaryKey(1L)).thenReturn(relation);
        when(sessionMapper.selectByPrimaryKey(1L)).thenReturn(session);
        when(memberService.getCurrentMember()).thenReturn(member);
        when(redissonClient.getSemaphore("seckill:semaphore:1")).thenReturn(semaphore);
        when(semaphore.tryAcquire(3, TimeUnit.SECONDS)).thenReturn(true);
        // 限购计数：预占与回滚两处取的是同一个key，恒返回同一个mock
        when(redissonClient.getAtomicLong("seckill:limit:1:1")).thenReturn(limitCounter);
        when(redissonClient.getBucket("seckill:stock:1", StringCodec.INSTANCE)).thenReturn(stockBucket);
        when(redissonClient.getScript()).thenReturn(script);
        // eval默认返回：第1次=扣库存成功剩9件；第2次=回滚补偿incr（按调用顺序生效）
        when(script.eval(any(), anyString(), any(), anyList())).thenReturn(9L, 1L);
    }

    // ==================== 测试用例 ====================

    /**
     * 用例1：秒杀关联不存在
     * 场景：relationMapper查不到（返回null）
     * 断言：抛ApiException，message为"秒杀商品不存在"
     */
    @Test
    public void testGenerateOrder_RelationNotFound() {
        // 覆盖默认行为：让relationMapper查不到这个关联（返回null）
        when(relationMapper.selectByPrimaryKey(1L)).thenReturn(null);

        // 执行被测方法并断言：抛出ApiException且消息为"秒杀商品不存在"
        ApiException e = assertThrows(ApiException.class,
                () -> flashPromotionOrderService.generateFlashPromotionOrder(param));
        assertEquals("秒杀商品不存在", e.getMessage());
    }

    /**
     * 用例2：不在秒杀场次时间内
     * 场景：sessionMapper查不到（返回null）
     * 断言：抛ApiException，message为"不在秒杀场次时间内"
     */
    @Test
    public void testGenerateOrder_SessionOutOfTime() {
        // 覆盖默认行为：场次查不到（返回null）
        when(sessionMapper.selectByPrimaryKey(1L)).thenReturn(null);

        // 断言：抛出ApiException且消息为"不在秒杀场次时间内"
        ApiException e = assertThrows(ApiException.class,
                () -> flashPromotionOrderService.generateFlashPromotionOrder(param));
        assertEquals("不在秒杀场次时间内", e.getMessage());
    }

    /**
     * 用例3：超过每人限购
     * 场景：limitCounter.incrementAndGet()返回2（>限购数1）
     * 断言：抛ApiException，message为"每人限购1件"；
     *      且回滚补偿执行——verify limitCounter.decrementAndGet()被调用1次
     */
    @Test
    public void testGenerateOrder_OverLimit() {
        // 覆盖：限购计数预占返回2（>限购数1，触发超限）
        when(limitCounter.incrementAndGet()).thenReturn(2L);

        // 断言：抛"每人限购1件"
        ApiException e = assertThrows(ApiException.class,
                () -> flashPromotionOrderService.generateFlashPromotionOrder(param));
        assertEquals("每人限购1件", e.getMessage());

        // 断言回滚：限购预占被撤销（decrementAndGet被调用1次）
        verify(limitCounter).decrementAndGet();
    }

    /**
     * 用例4：秒杀商品已抢完
     * 场景：limitCounter返回1（限购通过），但扣库存eval返回-1L
     * 断言：抛ApiException，message为"秒杀商品已抢完"；
     *      且库存未被扣减过——verify script.eval只被调用1次（没有补偿incr那第二次）
     */
    @Test
    public void testGenerateOrder_StockSoldOut() {
        // 覆盖：限购通过（返回1），但Lua扣库存返回-1（已抢完）
        when(limitCounter.incrementAndGet()).thenReturn(1L);
        when(script.eval(any(), anyString(), any(), anyList())).thenReturn(-1L);

        // 断言：抛"秒杀商品已抢完"
        ApiException e = assertThrows(ApiException.class,
                () -> flashPromotionOrderService.generateFlashPromotionOrder(param));
        assertEquals("秒杀商品已抢完", e.getMessage());

        // 断言：eval只调用1次——扣减失败不触发补偿（库存根本没扣成，无需还）
        verify(script, times(1)).eval(any(), anyString(), any(), anyList());
    }

    /**
     * 用例5：下单成功全链路
     * 场景：所有依赖正常——限购1、库存9、商品/库存/收货地址齐全、锁库存成功(1行)
     * 断言：返回的Map包含order键；
     *      且verify portalOrderDao.lockSkuStock(110L, 1)被调用；
     *      且verify portalOrderService.sendDelayMessageCancelOrder(any())被调用（发延时关单消息）
     */
    @Test
    public void testGenerateOrder_Success() {
        // 成功路径依赖的Mock：商品
        PmsProduct product = new PmsProduct();
        product.setId(26L);
        product.setName("华为P20");
        product.setPic("pic");
        when(productMapper.selectByPrimaryKey(26L)).thenReturn(product);
        // 商品SKU
        PmsSkuStock skuStock = new PmsSkuStock();
        skuStock.setId(110L);
        skuStock.setSkuCode("sku001");
        when(skuStockMapper.selectByExample(any())).thenReturn(Collections.singletonList(skuStock));
        // 收货地址（receiver_*非空字段）
        UmsMemberReceiveAddress address = new UmsMemberReceiveAddress();
        address.setName("张三");
        address.setPhoneNumber("18000000000");
        address.setPostCode("518000");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setRegion("南山区");
        address.setDetailAddress("科技园");
        when(memberReceiveAddressService.getItem(1L)).thenReturn(address);
        // 锁库存成功（1行）
        when(portalOrderDao.lockSkuStock(110L, 1)).thenReturn(1);

        // 执行：下单成功返回订单Map
        java.util.Map<String, Object> result =
                flashPromotionOrderService.generateFlashPromotionOrder(param);
        assertNotNull(result);
        assertTrue(result.containsKey("order"));

        // 行为断言：锁库存被调用、延时关单消息已发出
        verify(portalOrderDao).lockSkuStock(110L, 1);
        verify(portalOrderService).sendDelayMessageCancelOrder(any());
    }

    /**
     * 用例6：锁库存失败触发Redis回滚补偿
     * 场景：限购与库存扣减都成功，但锁SKU库存返回0行（库存不足）→ 业务抛异常
     * 断言：抛ApiException（"库存不足，无法下单"）；
     *      且库存补偿执行——verify script.eval被调用2次（扣减1次+补偿incr1次）；
     *      且限购回滚——verify limitCounter.decrementAndGet()被调用
     */
    @Test
    public void testGenerateOrder_RollbackOnDbFailure() {
        // 让流程走到锁库存一步（Mock准备同用例5）
        PmsProduct product = new PmsProduct();
        product.setId(26L);
        product.setName("华为P20");
        product.setPic("pic");
        when(productMapper.selectByPrimaryKey(26L)).thenReturn(product);
        PmsSkuStock skuStock = new PmsSkuStock();
        skuStock.setId(110L);
        skuStock.setSkuCode("sku001");
        when(skuStockMapper.selectByExample(any())).thenReturn(Collections.singletonList(skuStock));
        UmsMemberReceiveAddress address = new UmsMemberReceiveAddress();
        address.setName("张三");
        address.setPhoneNumber("18000000000");
        address.setPostCode("518000");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setRegion("南山区");
        address.setDetailAddress("科技园");
        when(memberReceiveAddressService.getItem(1L)).thenReturn(address);
        // 关键覆盖：锁库存失败（0行）
        when(portalOrderDao.lockSkuStock(110L, 1)).thenReturn(0);

        // 断言：抛"库存不足，无法下单"
        ApiException e = assertThrows(ApiException.class,
                () -> flashPromotionOrderService.generateFlashPromotionOrder(param));
        assertEquals("库存不足，无法下单", e.getMessage());

        // 断言回滚补偿：eval共2次（第1次扣库存成功，第2次补偿incr）
        verify(script, times(2)).eval(any(), anyString(), any(), anyList());
        // 断言限购回滚：预占被撤销
        verify(limitCounter).decrementAndGet();
    }
}
