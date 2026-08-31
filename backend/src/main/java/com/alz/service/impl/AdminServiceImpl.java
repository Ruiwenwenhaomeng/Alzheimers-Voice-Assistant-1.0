package com.alz.service.impl;

import com.alz.entity.User;
import com.alz.mapper.UserMapper;
import com.alz.mapper.UserProfileMapper;
import com.alz.service.AdminService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AdminServiceImpl implements AdminService {

    @Resource
    private UserMapper userMapper;

    @Resource
    private UserProfileMapper userProfileMapper;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public List<User> listAllUsers() {

        String cacheKey = "USER_LIST";

        List<User> cached =
                (List<User>) redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            return cached;
        }

        List<User> users = userMapper.listAll();

        redisTemplate.opsForValue().set(
                cacheKey,
                users,
                10,
                TimeUnit.MINUTES
        );

        return users;
    }

    @Override
    public void deleteUser(Long id) {
        // 先删除子表 user_profile
        userProfileMapper.deleteByUserId(id);

        // 再删除父表 user
        userMapper.deleteById(id);

        // 删除缓存
        redisTemplate.delete("USER_LIST");
    }
}