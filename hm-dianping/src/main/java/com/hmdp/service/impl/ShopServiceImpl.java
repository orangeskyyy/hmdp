package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private StringRedisTemplate stringRedisTemplate;

    private CacheClient cacheClient;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate,CacheClient cacheClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheClient = cacheClient;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Override
    public Result queryById(Long id) {

        // 空对象解决缓存穿透
        // Shop shop = queryWithPassThrough(id);
        // 互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);
        // 逻辑时间解决缓存击穿
        // Shop shop = queryWithLogicExpire(id);

        // Redis工具类
        // Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20l, TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * 逻辑过期处理缓存击穿
     * redis的过期时间实际是无线，
     * @param id
     * @return
     */
    private Shop queryWithLogicExpire(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        // 2. 未命中返回空
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        // 3. 命中后判断是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);

        // 3.1 未过期 返回商铺信息
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 4 过期 
        String lockKey = LOCK_SHOP_KEY + id;
        // 5 获取互斥锁
        boolean isLock = tryLock(lockKey);
        // 5.1 判断是否获取锁

            if (isLock) {
                // TODO 获取锁之后再判断缓存是否过期 没有过期则无需重建
                // 6 成功 创建新的线程重建redis
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        saveShop2Redis(id,20L);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } finally {
                        unLock(lockKey);
                    }
                });

            }

        // 6 失败 直接返回过期数据
        return shop;
    }

    /**
     * 缓存击穿
     * 一个高并发访问并且缓存重建业务比较复杂的key突然失效，无数的请求访问会在瞬间给数据库带来巨大的冲击
     * 互斥锁解决缓存穿透高并发下只有一个线程可以获取对redis的访问
     * @param id
     * @return
     */
    private Shop queryWithMutex(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询商铺信息
        // Map<Object, Object> shopMap = stringRedisTemplate.opsForHash().entries(shopKey);
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        // 2. 命中则返回信息
        if (StrUtil.isNotBlank(shopJson)) {
            // Shop shop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 2.1 此时命中空的字符串""
        if (shopJson != null) {
            return null;
        }
        Shop shop = null;

        // 4. 不存在则根据锁重建redis
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            // 4.1 尝试获取锁
            boolean res = tryLock(lockKey);
            // 4.2 判断是否获取锁
            if (!res) {
                // 4.3 失败则休眠一段时间
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4 成功根据id查数据库的信息
            shop = getById(id);
            // 模拟数据库查询的延时
            Thread.sleep(200);
            if (shop == null) {
                // redis创建空数据解决缓存穿透问题
                stringRedisTemplate.opsForValue().set(shopKey,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 5. 数据库存在则存入redis缓存中
            stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 6. 释放锁
            unLock(lockKey);
        }
        return shop;
    }

    /**
     * 核心思路就是利用redis的setnx方法来表示获取锁，
     * 该方法含义是redis中如果没有这个key，则插入成功，返回1，在stringRedisTemplate中返回true，
     * 如果有这个key则插入失败，则返回0，在stringRedisTemplate返回false，
     * 我们可以通过true，或者是false，来表示是否有线程成功插入key，成功插入的key的线程我们认为他就是获得到锁的线程。
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
    /**
     * 缓存穿透
     * 请求数据库不存在的数据缓存总是失效导致给数据库带来巨大请求压力
     * 使用缓存创建空对象来解决
     * @param id
     * @return
     */
    private Shop queryWithPassThrough(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询商铺信息
        // Map<Object, Object> shopMap = stringRedisTemplate.opsForHash().entries(shopKey);
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        // 2. 命中则返回信息
        if (StrUtil.isNotBlank(shopJson)) {
            // Shop shop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 2.1 此时命中空的字符串""
        if (shopJson != null) {
            return null;
        }

        // 3. 根据id查数据库的信息
        Shop shop = query().eq("id", id).one();
        // 4. 不存在返回错误信息
        if (shop == null) {
            // 4.1 redis创建空数据解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(shopKey,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5. 数据库存在则存入redis缓存中
        stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        // 1. 查询数据库
        Shop shop = getById(id);
        // 模拟数据库查询延时
        Thread.sleep(200);
        // 2. 添加逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }
}
