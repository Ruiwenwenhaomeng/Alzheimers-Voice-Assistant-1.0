package com.alz.service;

import com.alz.entity.UserProfile;

public interface UserProfileService {
    void saveOrUpdate(UserProfile profile);
    void saveOrUpdate_admin(UserProfile profile);
    UserProfile findByUserId(Long userId);
}