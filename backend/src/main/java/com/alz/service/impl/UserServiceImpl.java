package com.alz.service.impl;

import com.alz.entity.User;
import com.alz.entity.UserProfile;
import com.alz.mapper.UserMapper;
import com.alz.mapper.UserProfileMapper;
import com.alz.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserProfileMapper userProfileMapper;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper,
                           UserProfileMapper userProfileMapper,
                           PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.userProfileMapper = userProfileMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void register(User user) {
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()
                || user.getPassword() == null || user.getPassword().isBlank()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        user.setRole("USER");
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        userMapper.insert(user);
    }

    @Override
    public User login(String username, String password) {
        User user = userMapper.findByUsername(username);
        if (user == null || password == null || user.getPassword() == null) {
            return null;
        }

        String storedPassword = user.getPassword();
        boolean bcryptPassword = isBcrypt(storedPassword);
        boolean matches = bcryptPassword
                ? passwordEncoder.matches(password, storedPassword)
                : storedPassword.equals(password);

        if (!matches) {
            return null;
        }

        if (!bcryptPassword) {
            user.setPassword(passwordEncoder.encode(password));
            userMapper.updateById(user);
        }
        return user;
    }

    @Override
    public User findByUsername(String username) {
        return userMapper.findByUsername(username);
    }

    @Override
    public List<User> listAll() {
        return userMapper.listAll();
    }

    @Override
    public void deleteById(Long id) {
        // 先删除 profile
        userProfileMapper.deleteByUserId(id);
        // 再删除 user
        userMapper.deleteById(id);
    }

    @Override
    public void update(User user) {
        userMapper.update(user);
    }

    @Override
    public boolean changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null || oldPassword == null || newPassword == null || newPassword.isBlank()) {
            return false;
        }

        // 注意：生产环境应使用加密密码
        String storedPassword = user.getPassword();
        boolean matches = storedPassword != null && (isBcrypt(storedPassword)
                ? passwordEncoder.matches(oldPassword, storedPassword)
                : storedPassword.equals(oldPassword));
        if (!matches) return false;
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        return true;
    }

    @Override
    public boolean resetPassword(String username, String name, String phone, String newPassword) {
        User user = userMapper.findByUsername(username);
        if (user == null) return false;
        UserProfile profile = userProfileMapper.findByUserId(user.getId());
        if (profile == null) return false;

        if (!profile.getName().equals(name) || !profile.getPhone().equals(phone)) {
            return false;
        }
        if (newPassword == null || newPassword.isBlank()) return false;
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        return true;
    }

    private boolean isBcrypt(String password) {
        return password.startsWith("$2a$") || password.startsWith("$2b$") || password.startsWith("$2y$");
    }
}
