package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/*
基于StringRedisTemplate封装一个缓存工具类，满足下列需求：
    方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓
    存击穿问题
    方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
*/
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    // 方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long expireTime, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(value),expireTime,timeUnit);
    }

    // 方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicExpire(String key,Object value,Long expireTime,TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(expireTime)));

        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }


    /**
     * 方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     * @param keyPrefix key前缀
     * @param type 查询类型
     * @param dbFallback 数据库查询逻辑
     * @param time 过期时间
     * @param unit 过期时间单位
     * @param <ID> 查询id泛型
     * @param <R> 返回值泛型
     * @return
     */
    public <ID,R> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // redis缓存命中，非空串
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 判断是否命中空值
        if (json != null) {
            return null;
        }
        // 未命中空值
        // 先去数据库中查找
        R r = dbFallback.apply(id);
        // 数据库未命中
        if (r == null) {
            // 写空值解决缓存穿透
            set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        // 数据库
        this.set(key,r,time,unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    // 根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    public <ID,R> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,
                                           Function<ID,R> dbFallBack,Long time,TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        // 未过期直接返回
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }

        // 过期就更新时间，商品数据库查询锁
        String lockKey = LOCK_SHOP_KEY + id;
        // 获取更新redis缓存的互斥锁
        if (tryLock(lockKey)) {
            try {
                // 新线程去读数据库更新redis
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    // 数据库逻辑同样是由参数给出
                    R r1 = dbFallBack.apply(id);
                    setWithLogicExpire(key,r1,time,unit);
                });
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                unlock(lockKey);
            }
        }
        // 更新缓存的线程去更新，查询的线程依旧返回旧数据
        return r;

    }
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    public <ID,R> R queryWithMutex(String keyPrefix, ID id, Class<R> type,Function<ID,R> dbFallBack,
                                   Long time,TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json,type);
        }
        if (json != null) {
            // 空值命中，返回空对象
            return null;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 获取锁失败
                Thread.sleep(50);
                return queryWithMutex(keyPrefix,id,type,dbFallBack,time,unit);
            }
            // 数据库查询
            r = dbFallBack.apply(id);
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            this.set(key,r,time,unit);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            unlock(lockKey);
        }
        return r;
    }


}
