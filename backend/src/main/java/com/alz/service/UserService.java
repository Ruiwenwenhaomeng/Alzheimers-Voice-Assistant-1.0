package com.alz.service;

import com.alz.entity.User;

import java.util.List;

public interface UserService {
    void register(User user);
    User login(String username, String password);
    User findByUsername(String username);
    List<User> listAll();
    void deleteById(Long id);
    void update(User user);
    public boolean changePassword(Long userId, String oldPassword, String newPassword);
    public boolean resetPassword(String username, String name, String phone, String newPassword);
}