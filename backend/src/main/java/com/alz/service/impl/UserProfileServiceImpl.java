package com.alz.service.impl;

import com.alz.entity.UserProfile;
import com.alz.mapper.UserProfileMapper;
import com.alz.service.UserProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserProfileServiceImpl implements UserProfileService {

    @Autowired
    private UserProfileMapper mapper;

    @Override
    public void saveOrUpdate(UserProfile profile) {
        UserProfile existing = mapper.findByUserId(profile.getUserId());
        if (existing == null) {
            mapper.insert(profile);
        } else {
            mapper.update(profile);
        }
    }

    @Override
    public void saveOrUpdate_admin(UserProfile profile) {
        UserProfile existing = mapper.findByUserId(profile.getUserId());
        if (existing == null) {
            mapper.insert(profile);
        } else {
            mapper.update_admin(profile);
        }
    }

    @Override
    public UserProfile findByUserId(Long userId) {
        return mapper.findByUserId(userId);
    }

}