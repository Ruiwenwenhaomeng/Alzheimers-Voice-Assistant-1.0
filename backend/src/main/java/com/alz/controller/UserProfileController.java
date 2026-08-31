package com.alz.controller;

import com.alz.entity.User;
import com.alz.entity.UserProfile;
import com.alz.service.UserProfileService;
import com.alz.service.UserService;
import com.alz.config.JwtUtil;
import com.alz.config.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/profile")  // 前端访问 /user/profile/xxx
@RequireRole("USER")
public class UserProfileController {

    @Autowired
    private UserProfileService profileService;

    @Autowired
    private UserService userService;

    /**
     * 保存或更新用户信息
     * 前端 POST http://localhost:8080/user/profile/update
     */
    @PostMapping("/update")
    public Object updateProfile(@RequestBody UserProfile profile,
                                HttpServletRequest request) {

        String tokenHeader = request.getHeader("Authorization");
        if (tokenHeader == null || !tokenHeader.startsWith("Bearer ")) {
            return "未登录";
        }

        String token = tokenHeader.substring(7);
        String username = JwtUtil.getUsername(token);

        User user = userService.findByUsername(username);

        profile.setUserId(user.getId());

        // 保留只读量表分数
        UserProfile existing = profileService.findByUserId(user.getId());
        if (existing != null) {
            profile.setMmse(existing.getMmse());
            profile.setMoca(existing.getMoca());
            profile.setHkbc(existing.getHkbc());
        }

        profileService.saveOrUpdate(profile);
        return "保存成功";
    }

    /**
     * 获取当前用户信息
     * 前端 GET http://localhost:8080/user/profile/get
     */
    @GetMapping("/get")
    public Object getProfile(HttpServletRequest request) {
        String tokenHeader = request.getHeader("Authorization");
        if (tokenHeader == null || !tokenHeader.startsWith("Bearer ")) {
            return "未登录";
        }

        String token = tokenHeader.substring(7);
        String username = JwtUtil.getUsername(token);
        User user = userService.findByUsername(username);
        System.out.println("用户" + username + " 申请查看信息");
        return profileService.findByUserId(user.getId());
    }
}
