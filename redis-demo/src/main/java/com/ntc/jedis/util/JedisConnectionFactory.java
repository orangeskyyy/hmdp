package com.ntc.jedis.util;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class JedisConnectionFactory {
    public static JedisPool jedisPool;

    static {
        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
        jedisPoolConfig.setMaxTotal(8);
        jedisPoolConfig.setMaxIdle(8);
        jedisPoolConfig.setMinIdle(0);
        jedisPoolConfig.setMaxWaitMillis(1000);
        // GenericObjectPoolConfig<Jedis> poolConfig, String host, int port, int timeout, String password, int database
        jedisPool = new JedisPool(jedisPoolConfig,"192.168.170.128",6379,1000,"1234");
    }

    public static Jedis getJedisPool() {
        return jedisPool.getResource();
    }
}
