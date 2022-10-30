package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private StringRedisTemplate stringRedisTemplate;

    public ShopTypeServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取所有商户类型
     * @return
     */
    @Override
    public Result queryTypeList() {
        String key = CACHE_SHOPTYPE_KEY;
        // 查询redis
        Set<String> stringTypeList = stringRedisTemplate.opsForZSet().range(key, 0, -1);
        // 缓存命中
        if (CollUtil.isNotEmpty(stringTypeList)) {
            List<ShopType> typeList = new ArrayList<>();
            stringTypeList.stream().forEach(e ->
                    typeList.add(JSONUtil.toBean(e,ShopType.class)));
            return Result.ok(typeList);
        }
        // 未命中
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (CollUtil.isEmpty(typeList)) {
            return Result.fail("商铺类型不存在");
        }

        typeList.stream().forEach(e ->
                stringRedisTemplate.opsForZSet().add(key,
                        JSONUtil.toJsonStr(e),e.getSort()));

        return Result.ok(typeList);
    }
}
