package com.ntc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntc.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.clients.jedis.Jedis;

@SpringBootTest()
class RedisDemoApplicationTests {


    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 手动序列化
    private static final ObjectMapper mp = new ObjectMapper();
    // public RedisDemoApplicationTests(RedisTemplate redisTemplate) {
    //     this.redisTemplate = redisTemplate;
    // }

    private Jedis jedis;

    @BeforeEach
    void setUp() {
        jedis = new Jedis("192.168.170.128", 6379);
        jedis.auth("1234");
        jedis.select(0);
    }

    @AfterEach
    void tearDown() {
        if (jedis != null) {
            jedis.close();
        }
    }

    @Test
    void testJedis() {
        String res = jedis.set("name", "虎哥");
        System.out.println("res="+res);
        String name = jedis.get("name");
        System.out.println("name="+name);
    }
    @Test
    void testString() {
        redisTemplate.opsForValue().set("name","tiger");
        Object name = redisTemplate.opsForValue().get("name");
        String name2 = stringRedisTemplate.opsForValue().get("name");
        System.out.println("name = " + name);
        System.out.println("name2 = " + name2);
    }

    @Test
    void testJson() {
        User user = new User();
        user.setName("老王");
        user.setAge(033);
        redisTemplate.opsForValue().set("user:1",user);
    }

    @Test
    void testSaveUser() throws JsonProcessingException {
        User user = new User();
        user.setName("虎哥");
        user.setAge(32);
        // 手动序列化
        String userString = mp.writeValueAsString(user);
        stringRedisTemplate.opsForValue().set("user:1",userString);

        // 获取数据
        String jsonUser = stringRedisTemplate.opsForValue().get("user:1");
        User user1 = mp.readValue(jsonUser, User.class);
        System.out.println("user1:" + user1);
    }
}
