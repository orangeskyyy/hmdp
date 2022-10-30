package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;


public class RefreshInterceptor implements HandlerInterceptor{
    private StringRedisTemplate stringRedisTemplate;

    public RefreshInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("authorization");
        // 针对所有访问不存在token则直接放行
        if (StrUtil.isBlank(token)) {
            return true;
        }
        String key = LOGIN_USER_KEY + token;

        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        if (CollUtil.isEmpty(userMap)) {
            return true;
        }
        // 存在用户对象就保存到threadLocal中
        // map转DTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 存储userDTO对象
        UserHolder.saveUser(userDTO);
        // 刷新token有效期
        stringRedisTemplate.expire(key,LOGIN_USER_TTL,TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
