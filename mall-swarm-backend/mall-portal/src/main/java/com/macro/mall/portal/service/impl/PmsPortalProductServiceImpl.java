package com.macro.mall.portal.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.github.pagehelper.PageHelper;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.mapper.*;
import com.macro.mall.model.*;
import com.macro.mall.portal.dao.PortalProductDao;
import com.macro.mall.portal.domain.PmsPortalProductDetail;
import com.macro.mall.portal.domain.PmsProductCategoryNode;
import com.macro.mall.portal.service.PmsPortalProductService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 前台订单管理Service实现类
 * Created by macro on 2020/4/6.
 */
@Service
public class PmsPortalProductServiceImpl implements PmsPortalProductService {

    // ==================== 缓存三兄弟相关配置 ====================
    private static final String PRODUCT_DETAIL_CACHE_PREFIX = "portal:product:detail:";
    // 正常缓存基础过期：30分钟
    private static final long CACHE_EXPIRE_BASE = 30 * 60L;
    // 随机过期上限：5分钟（RedisService的set时间单位是秒）
    private static final long CACHE_EXPIRE_RANDOM = 5 * 60L;
    // 空值缓存过期：5分钟
    private static final long NULL_CACHE_EXPIRE = 5 * 60L;
    // 空值缓存内容：哨兵字符串，读到它代表"这个id确认查无此商品"
    private static final String NULL_CACHE_VALUE = "NULL";

    @Autowired
    private PmsProductMapper productMapper;
    @Autowired
    private PmsProductCategoryMapper productCategoryMapper;
    @Autowired
    private PmsBrandMapper brandMapper;
    @Autowired
    private PmsProductAttributeMapper productAttributeMapper;
    @Autowired
    private PmsProductAttributeValueMapper productAttributeValueMapper;
    @Autowired
    private PmsSkuStockMapper skuStockMapper;
    @Autowired
    private PmsProductLadderMapper productLadderMapper;
    @Autowired
    private PmsProductFullReductionMapper productFullReductionMapper;
    @Autowired
    private PortalProductDao portalProductDao;
    @Autowired
    private RedisService redisService;
    @Autowired
    private RedissonClient redissonClient;

    @Override
    public List<PmsProduct> search(String keyword, Long brandId, Long productCategoryId, Integer pageNum, Integer pageSize, Integer sort) {
        PageHelper.startPage(pageNum, pageSize);
        PmsProductExample example = new PmsProductExample();
        PmsProductExample.Criteria criteria = example.createCriteria();
        criteria.andDeleteStatusEqualTo(0);
        if (StrUtil.isNotEmpty(keyword)) {
            criteria.andNameLike("%" + keyword + "%");
        }
        if (brandId != null) {
            criteria.andBrandIdEqualTo(brandId);
        }
        if (productCategoryId != null) {
            criteria.andProductCategoryIdEqualTo(productCategoryId);
        }
        //1->按新品；2->按销量；3->价格从低到高；4->价格从高到低
        if (sort == 1) {
            example.setOrderByClause("id desc");
        } else if (sort == 2) {
            example.setOrderByClause("sale desc");
        } else if (sort == 3) {
            example.setOrderByClause("price asc");
        } else if (sort == 4) {
            example.setOrderByClause("price desc");
        }
        return productMapper.selectByExample(example);
    }

    @Override
    public List<PmsProductCategoryNode> categoryTreeList() {
        PmsProductCategoryExample example = new PmsProductCategoryExample();
        List<PmsProductCategory> allList = productCategoryMapper.selectByExample(example);
        List<PmsProductCategoryNode> result = allList.stream()
                .filter(item -> item.getParentId().equals(0L))
                .map(item -> covert(item, allList)).collect(Collectors.toList());
        return result;
    }

    @Override
    public PmsPortalProductDetail detail(Long id) {
        String cacheKey = PRODUCT_DETAIL_CACHE_PREFIX + id;

        // ┌─ 【缓存命中检查】───────────────────────────────────
        // │ 有缓存直接返回，一次DB都不碰
        // └──────────────────────────────────────────────────
        Object cached = redisService.get(cacheKey);
        // 如果缓存命中且类型正确，直接返回。
        if (cached instanceof PmsPortalProductDetail){
            // 这是最好的情况。如果拿到的 cached 确实是我们想要的商品详情类型，
            // 那就证明缓存里有数据，直接把它转成商品对象返回。一次数据库都不用查。
            return (PmsPortalProductDetail) cached;
        }

        if (NULL_CACHE_VALUE.equals(cached)){
            // 【缓存穿透的对策生效点】：命中空值缓存，代表这个id确认不存在，
            // 直接返回null，不再查DB。不存在的id不会每次都白打一次数据库
            return null;
        }

        // ┌─ 【防缓存击穿：互斥锁】─────────────────────────────
        // │ 解决的问题：热点key过期的那一瞬间，几十个请求同时miss，
        // │ 同时回源查DB，把数据库打爆。
        // │ 对策：只让一个线程拿到锁去回源，其余线程拿不到锁就重读缓存
        // └──────────────────────────────────────────────────
        // 因为缓存里没数据，需要有人去数据库查。我们只允许一个人去查，防止几十个人同时涌进去。这个锁就是“通行证”。
        RLock lock = redissonClient.getLock("lock:cache:product:" + id);
        boolean locked = false;
        try{
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!locked){
            // 没拿到锁，说明别人正在查数据库，短暂等待后重读一次缓存
            sleepQuietly(100);
            Object retry = redisService.get(cacheKey);
            if (retry instanceof PmsPortalProductDetail){
                return (PmsPortalProductDetail) retry;
            }
            Asserts.fail("系统繁忙，请稍后再试");
        }

        // 拿到锁
        try{
            // 【双重检查】：拿到锁后再读一次缓存。
            // 排在前面的线程可能刚回源并写好了缓存，直接返回，省一次DB查询
            Object doubleCheck = redisService.get(cacheKey);
            if (doubleCheck instanceof PmsPortalProductDetail){
                return (PmsPortalProductDetail) doubleCheck;
            }

            // 拿到锁之后，如果发现缓存里存的是“空值标记”（NULL_CACHE_VALUE），就直接返回 null，代表“这个商品不存在”。
            if (NULL_CACHE_VALUE.equals(doubleCheck)){
                return null;
            }

            // 【回源DB】：全系统同一时刻只有这个线程走到这里
            PmsPortalProductDetail detail = getDetailFromDb(id);
            if (detail != null){
                // ┌─ 【防缓存雪崩：过期时间加随机值】───────────────
                // │ 解决的问题：大量key在同一时刻过期，会一起回源把DB打垮
                // │ 对策：每个key的过期时间 = 30分钟 + 随机0~5分钟，错峰过期
                // └────────────────────────────────────────────
                long expire = CACHE_EXPIRE_BASE + ThreadLocalRandom.current().nextLong(CACHE_EXPIRE_RANDOM);
                redisService.set(cacheKey, detail, expire);
                return detail;
            }
            // 【防缓存穿透：空值缓存】查无此商品也缓存5分钟
            redisService.set(cacheKey, NULL_CACHE_VALUE, NULL_CACHE_EXPIRE);
            return null;
        } finally {
            // 只释放自己还持有的锁（租约超时保护，和秒杀/关单里的处理一致）
            if (lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PmsPortalProductDetail getDetailFromDb(Long id) {
        PmsPortalProductDetail result = new PmsPortalProductDetail();
        //获取商品信息
        PmsProduct product = productMapper.selectByPrimaryKey(id);
        if (product == null) {
            return null; // 查无此商品：返回null，由外层写空值缓存
        }
        result.setProduct(product);
        //获取品牌信息
        PmsBrand brand = brandMapper.selectByPrimaryKey(product.getBrandId());
        result.setBrand(brand);
        //获取商品属性信息
        PmsProductAttributeExample attributeExample = new PmsProductAttributeExample();
        attributeExample.createCriteria().andProductAttributeCategoryIdEqualTo(product.getProductAttributeCategoryId());
        List<PmsProductAttribute> productAttributeList = productAttributeMapper.selectByExample(attributeExample);
        result.setProductAttributeList(productAttributeList);
        //获取商品属性值信息
        if(CollUtil.isNotEmpty(productAttributeList)){
            List<Long> attributeIds = productAttributeList.stream().map(PmsProductAttribute::getId).collect(Collectors.toList());
            PmsProductAttributeValueExample attributeValueExample = new PmsProductAttributeValueExample();
            attributeValueExample.createCriteria().andProductIdEqualTo(product.getId())
                    .andProductAttributeIdIn(attributeIds);
            List<PmsProductAttributeValue> productAttributeValueList = productAttributeValueMapper.selectByExample(attributeValueExample);
            result.setProductAttributeValueList(productAttributeValueList);
        }
        //获取商品SKU库存信息
        PmsSkuStockExample skuExample = new PmsSkuStockExample();
        skuExample.createCriteria().andProductIdEqualTo(product.getId());
        List<PmsSkuStock> skuStockList = skuStockMapper.selectByExample(skuExample);
        result.setSkuStockList(skuStockList);
        //商品阶梯价格设置
        if(product.getPromotionType()==3){
            PmsProductLadderExample ladderExample = new PmsProductLadderExample();
            ladderExample.createCriteria().andProductIdEqualTo(product.getId());
            List<PmsProductLadder> productLadderList = productLadderMapper.selectByExample(ladderExample);
            result.setProductLadderList(productLadderList);
        }
        //商品满减价格设置
        if(product.getPromotionType()==4){
            PmsProductFullReductionExample fullReductionExample = new PmsProductFullReductionExample();
            fullReductionExample.createCriteria().andProductIdEqualTo(product.getId());
            List<PmsProductFullReduction> productFullReductionList = productFullReductionMapper.selectByExample(fullReductionExample);
            result.setProductFullReductionList(productFullReductionList);
        }
        //商品可用优惠券
        result.setCouponList(portalProductDao.getAvailableCouponList(product.getId(),product.getProductCategoryId()));
        return result;
    }


    /**
     * 初始对象转化为节点对象
     */
    private PmsProductCategoryNode covert(PmsProductCategory item, List<PmsProductCategory> allList) {
        PmsProductCategoryNode node = new PmsProductCategoryNode();
        BeanUtils.copyProperties(item, node);
        List<PmsProductCategoryNode> children = allList.stream()
                .filter(subItem -> subItem.getParentId().equals(item.getId()))
                .map(subItem -> covert(subItem, allList)).collect(Collectors.toList());
        node.setChildren(children);
        return node;
    }
}
