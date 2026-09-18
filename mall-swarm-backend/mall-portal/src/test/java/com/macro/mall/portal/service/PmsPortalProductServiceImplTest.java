package com.macro.mall.portal.service;

import com.macro.mall.common.exception.ApiException;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.mapper.*;
import com.macro.mall.model.*;
import com.macro.mall.portal.dao.PortalProductDao;
import com.macro.mall.portal.domain.PmsPortalProductDetail;
import com.macro.mall.portal.service.impl.PmsPortalProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 商品详情缓存三兄弟单元测试
 * <p>
 * 覆盖缓存外壳的全部决策分支：命中/空值命中/双重检查/回源写缓存（随机过期区间）/
 * 拿锁失败重读/查无此商品写空值缓存。
 * 设计思想：Redis与Redisson全部Mock，只验证"决策逻辑"本身；
 * 异步编排的ExecutorService用同步执行替身（任务直接run），避免Future永不完成。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class PmsPortalProductServiceImplTest {

    @InjectMocks
    private PmsPortalProductServiceImpl productService;

    @Mock
    private RedisService redisService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private ExecutorService businessExecutor;

    // getDetailFromDb内部的Mapper链（异步六路）
    @Mock
    private PmsProductMapper productMapper;
    @Mock
    private PmsBrandMapper brandMapper;
    @Mock
    private PmsProductAttributeMapper productAttributeMapper;
    @Mock
    private PmsProductAttributeValueMapper productAttributeValueMapper;
    @Mock
    private PmsSkuStockMapper skuStockMapper;
    @Mock
    private PmsProductLadderMapper productLadderMapper;
    @Mock
    private PmsProductFullReductionMapper productFullReductionMapper;
    @Mock
    private PortalProductDao portalProductDao;

    private static final Long PRODUCT_ID = 26L;
    private static final String CACHE_KEY = "portal:product:detail:26";

    private PmsPortalProductDetail cachedDetail;

    @BeforeEach
    public void setUp() throws InterruptedException {
        cachedDetail = new PmsPortalProductDetail();
        cachedDetail.setProduct(new PmsProduct());

        // 互斥锁默认：拿得到、自己持有
        when(redissonClient.getLock("lock:cache:product:" + PRODUCT_ID)).thenReturn(lock);
        when(lock.tryLock(3, 10, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        // 异步编排的Executor替身：任务直接同步run（否则Future永不完成，join挂起）
        // 注意：execute是void方法，必须用doAnswer(...).when(...)语法
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(businessExecutor).execute(any());

        // 回源DB的默认数据链（成功路径）：商品→品牌→空属性→空SKU→空优惠券（promotionType=1不查阶梯/满减）
        PmsProduct product = new PmsProduct();
        product.setId(PRODUCT_ID);
        product.setBrandId(3L);
        product.setProductAttributeCategoryId(1L);
        product.setProductCategoryId(19L);
        product.setPromotionType(1);
        product.setName("华为P20");
        product.setPic("pic");
        when(productMapper.selectByPrimaryKey(PRODUCT_ID)).thenReturn(product);
        when(brandMapper.selectByPrimaryKey(3L)).thenReturn(new PmsBrand());
        when(productAttributeMapper.selectByExample(any())).thenReturn(Collections.emptyList());
        when(skuStockMapper.selectByExample(any())).thenReturn(Collections.emptyList());
        when(portalProductDao.getAvailableCouponList(eq(PRODUCT_ID), any())).thenReturn(Collections.emptyList());
    }

    /**
     * 用例1：缓存命中——直接返回缓存值，不查DB、不拿锁
     */
    @Test
    public void testDetail_CacheHit() throws InterruptedException {
        when(redisService.get(CACHE_KEY)).thenReturn(cachedDetail);

        PmsPortalProductDetail result = productService.detail(PRODUCT_ID);

        assertSame(cachedDetail, result);
        verify(productMapper, never()).selectByPrimaryKey(any()); // 零DB查询
        verify(lock, never()).tryLock(anyLong(), anyLong(), any()); // 锁都没拿
    }

    /**
     * 用例2：空值缓存命中——返回null（穿透防护：确认不存在的id不再查DB）
     */
    @Test
    public void testDetail_NullCacheHit() {
        when(redisService.get(CACHE_KEY)).thenReturn("NULL");

        PmsPortalProductDetail result = productService.detail(PRODUCT_ID);

        assertNull(result);
        verify(productMapper, never()).selectByPrimaryKey(any());
    }

    /**
     * 用例3：缓存miss + 拿锁 + 双重检查命中——返回缓存值，不查DB（击穿防护核心）
     */
    @Test
    public void testDetail_DoubleCheckHit() {
        // 第一次读缓存miss，拿到锁后第二次读命中（别人已回源）
        when(redisService.get(CACHE_KEY)).thenReturn(null, cachedDetail);

        PmsPortalProductDetail result = productService.detail(PRODUCT_ID);

        assertSame(cachedDetail, result);
        verify(productMapper, never()).selectByPrimaryKey(any()); // 双重检查命中，省掉回源
        verify(lock).unlock(); // 锁正常释放
    }

    /**
     * 用例4：缓存miss + 双重检查miss + 回源成功——写缓存且过期时间落在30~35分钟随机区间（防雪崩）
     */
    @Test
    public void testDetail_MissAndRebuild() {
        when(redisService.get(CACHE_KEY)).thenReturn(null, null);

        PmsPortalProductDetail result = productService.detail(PRODUCT_ID);

        assertNotNull(result);
        // 断言写缓存的过期时间在 [1800, 2100) 秒（30分钟+随机0~5分钟）
        verify(redisService).set(eq(CACHE_KEY), any(), longThat(expire -> expire >= 1800 && expire < 2100));
        verify(lock).unlock();
    }

    /**
     * 用例5：拿不到锁——短暂等待后重读缓存命中返回（别人正在回源，自己不回源）
     */
    @Test
    public void testDetail_LockFailedThenHit() throws InterruptedException {
        when(lock.tryLock(3, 10, TimeUnit.SECONDS)).thenReturn(false);
        // 拿锁失败后重读一次缓存：命中
        when(redisService.get(CACHE_KEY)).thenReturn(null, cachedDetail);

        PmsPortalProductDetail result = productService.detail(PRODUCT_ID);

        assertSame(cachedDetail, result);
        verify(productMapper, never()).selectByPrimaryKey(any());
    }

    /**
     * 用例6：回源查无此商品——写空值缓存（5分钟），返回null
     */
    @Test
    public void testDetail_ProductNotFound() {
        when(redisService.get(CACHE_KEY)).thenReturn(null, null);
        when(productMapper.selectByPrimaryKey(PRODUCT_ID)).thenReturn(null);

        PmsPortalProductDetail result = productService.detail(PRODUCT_ID);

        assertNull(result);
        // 断言写空值缓存：哨兵"NULL" + 300秒过期
        verify(redisService).set(eq(CACHE_KEY), eq("NULL"), eq(300L));
        verify(lock).unlock();
    }

    /**
     * 用例7：拿不到锁且重读仍miss——快速失败"系统繁忙"（防无限等待）
     */
    @Test
    public void testDetail_LockFailedThenMiss() throws InterruptedException {
        when(lock.tryLock(3, 10, TimeUnit.SECONDS)).thenReturn(false);
        when(redisService.get(CACHE_KEY)).thenReturn(null, null);

        assertThrows(ApiException.class, () -> productService.detail(PRODUCT_ID));
        verify(productMapper, never()).selectByPrimaryKey(any());
    }
}
