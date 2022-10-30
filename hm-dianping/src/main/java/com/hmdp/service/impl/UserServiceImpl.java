package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 用户在提交手机号后，会校验手机号是否合法，
        // 如果不合法，则要求用户重新输入手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("注册手机号无效");
        }

        // 如果手机号合法，后台此时生成对应的验证码，同时将验证码进行保存，
        String code = RandomUtil.randomNumbers(6);
        // redis存储验证码改善session存储存在的tomcat服务器集群下数据不一致
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 然后再通过短信的方式将验证码发送给用户
        log.debug("将code通过短信方式发送:{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 用户将验证码和手机号进行输入，
        // 校验手机号和验证码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("登录手机号无效");
        }

        if (RegexUtils.isCodeInvalid(loginForm.getCode())) {
            return Result.fail("验证码格式错误");
        }
        // 从redis取出cacheCode
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        // 如果不一致，则无法通过校验，
        if (cacheCode == null || !loginForm.getCode().equals(cacheCode)) {
            return Result.fail("验证码错误");
        }

        // 如果一致，则后台根据手机号查询用户，
        User user = query().eq("phone", phone).one();
        // 如果用户不存在，则为用户创建账号信息，保存到数据库，
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        // 无论是否存在，都会将用户信息保存到session中，方便后续获得当前登录信息
        // session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 保存到redis中
        String token = UUID.randomUUID().toString();
        String tokenKey = LOGIN_USER_KEY + token;
        // stringRedisTemplate属性都必须是String类型的
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .ignoreNullValue()
                        .setFieldValueEditor((fieldName,fieldValue) ->
                                fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        // 设置token有效期，模拟session
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        // 存在问题：当前的有效期是只要到了30min就会删除redis
        // 解决方法：在拦截器中，添加刷新expire，每次请求都会先被拦截
        // 返回token给客户端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(7));
        save(user);
        return user;
    }
}
