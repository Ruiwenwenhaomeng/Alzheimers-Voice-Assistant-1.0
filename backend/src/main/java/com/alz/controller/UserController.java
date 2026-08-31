package com.alz.controller;

import com.alz.entity.User;
import com.alz.service.UserService;
import com.alz.config.JwtUtil;
import com.alz.config.RequireRole;
import com.alz.config.StoragePaths;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;

import jakarta.annotation.Resource;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private StoragePaths storagePaths;

    // ----------------------
    // 注册接口（无需登录）
    // ----------------------
    @PostMapping(path = "/register", produces = "application/json;charset=UTF-8")
    public String register(@RequestBody User user) {
        try {
            userService.register(user);
            return "注册成功";
        } catch (Exception e) {
            return "用户名已存在";
        }
    }

    // ----------------------
    // 找回密码接口（未登录）
    // ----------------------
    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@RequestBody Map<String, String> body) {

        boolean success = userService.resetPassword(
                body.get("username"),
                body.get("name"),
                body.get("phone"),
                body.get("newPassword")
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "密码重置成功" : "姓名或手机号不匹配");

        return result;
    }

    // ----------------------
    // 登录接口（无需登录）
    // ----------------------
    @PostMapping("/login")
    public String login(@RequestBody User user) {

        String key = "LOGIN_FAIL:" + user.getUsername();
        String failStr = stringRedisTemplate.opsForValue().get(key);

        if (failStr != null && Long.parseLong(failStr) >= 5) {
            return "账号已锁定，请10分钟后再试";
        }

        User dbUser = userService.login(user.getUsername(), user.getPassword());

        if (dbUser == null) {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                stringRedisTemplate.expire(key, 10, TimeUnit.MINUTES);
            }
            return "用户名或密码错误";
        }

        stringRedisTemplate.delete(key);

        String token = JwtUtil.generateToken(dbUser.getUsername(), dbUser.getRole());
        String username = dbUser.getUsername();
        String role = dbUser.getRole();

        // 删除旧 token
        String oldToken = stringRedisTemplate.opsForValue().get("USER_TOKEN:" + username);
        if (oldToken != null) {
            stringRedisTemplate.delete("TOKEN:" + oldToken);
        }

        stringRedisTemplate.opsForValue().set("TOKEN:" + token, role, 30, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set("USER_TOKEN:" + username, token, 30, TimeUnit.MINUTES);

        return token;
    }

    // ----------------------
    // 修改密码接口（已登录）
    // ----------------------
    @RequireRole("USER")
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestHeader("Authorization") String token,
                                 @RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        String username = JwtUtil.getUsername(token.replace("Bearer ", "").trim());
        User user = userService.findByUsername(username);
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        boolean success = userService.changePassword(user.getId(), oldPassword, newPassword);

        result.put("success", success);
        result.put("message", success ? "修改成功" : "旧密码错误");
        return result;
    }

    // ----------------------
    // 检测接口（需要登录普通用户）
    // ----------------------
    @RequireRole("USER")
    @PostMapping("/detect")
    public Map<String,Object> detect(@RequestBody Map<String,String> body,
                                     @RequestHeader("Authorization") String token) {

        String fileName = body.get("fileName");
        if (fileName == null || fileName.isEmpty()) {
            throw new RuntimeException("文件名不能为空");
        }

        String fullPath;
        try {
            fullPath = storagePaths.resolveAudio(fileName).toString();
        } catch (IllegalArgumentException exception) {
            throw new RuntimeException("文件名不合法");
        }

        RestTemplate restTemplate = new RestTemplate();
        MultiValueMap<String,Object> map = new LinkedMultiValueMap<>();
        map.add("file", new FileSystemResource(fullPath));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String,Object>> request = new HttpEntity<>(map, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "http://localhost:8000/detect",
                request,
                Map.class
        );

        // 总检测次数 +1
        stringRedisTemplate.opsForValue().increment("DETECT_TOTAL");

        // 当前用户 +1
        String pureToken = token.replace("Bearer ", "").trim();
        String username = JwtUtil.getUsername(pureToken);
        stringRedisTemplate.opsForValue().increment("DETECT_USER:" + username);

        return response.getBody();
    }

}
