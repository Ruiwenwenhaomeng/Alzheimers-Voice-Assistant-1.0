package com.alz.controller;

import com.alz.entity.AudioRecord;
import com.alz.entity.User;
import com.alz.entity.UserFullInfo;
import com.alz.service.AdminService;
import com.alz.service.AudioService;
import com.alz.service.UserService;
import com.alz.service.UserProfileService;
import com.alz.entity.UserProfile;
import com.alz.config.JwtUtil;
import com.alz.config.AudioMediaTypes;
import com.alz.config.RequireRole;
import com.alz.config.StoragePaths;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.RestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/admin")
@RequireRole("ADMIN")
public class AdminController {

    @Autowired
    private UserService userService;

    @Resource
    private AdminService adminService;

    @Autowired
    private AudioService audioService;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private StoragePaths storagePaths;

    // ----------------------
    // 用户管理
    // ----------------------
    @RequireRole("ADMIN")
    @GetMapping("/users")
    public Object listUsers() {
        return adminService.listAllUsers();
    }

    @RequireRole("ADMIN")
    @GetMapping("/user/{id}")
    public Object getUserProfile(@PathVariable Long id) {
        return userProfileService.findByUserId(id);
    }

    @RequireRole("ADMIN")
    @DeleteMapping("/user/{id}")
    public Object deleteUser(@PathVariable Long id) {
        adminService.deleteUser(id);
        return "删除成功";
    }

    @RequireRole("ADMIN")
    @PostMapping("/user/full/update")
    public Object updateFullUser(@RequestBody UserFullInfo info) {

        User user = new User();
        user.setId(info.getId());
        user.setUsername(info.getUsername());
        user.setRole(info.getRole());
        userService.update(user);

        UserProfile profile = new UserProfile();
        profile.setUserId(info.getId());
        profile.setName(info.getName());
        profile.setGender(info.getGender());
        profile.setAge(info.getAge());
        profile.setPhone(info.getPhone());
        profile.setMedicalHistory(info.getMedicalHistory());
        profile.setMmse(info.getMmse());
        profile.setMoca(info.getMoca());
        profile.setHkbc(info.getHkbc());

        userProfileService.saveOrUpdate_admin(profile);

        return "修改成功";
    }

    @RequireRole("ADMIN")
    @GetMapping("/users/full")
    public Object listFullUsers() {

        List<User> users = userService.listAll();
        List<UserFullInfo> result = new ArrayList<>();

        for (User user : users) {
            UserFullInfo info = new UserFullInfo();
            info.setId(user.getId());
            info.setUsername(user.getUsername());
            info.setRole(user.getRole());

            UserProfile profile = userProfileService.findByUserId(user.getId());
            if (profile != null) {
                info.setName(profile.getName());
                info.setGender(profile.getGender());
                info.setAge(profile.getAge());
                info.setPhone(profile.getPhone());
                info.setMedicalHistory(profile.getMedicalHistory());
                info.setMmse(profile.getMmse());
                info.setMoca(profile.getMoca());
                info.setHkbc(profile.getHkbc());
            }
            result.add(info);
        }
        return result;
    }

    // ----------------------
    // 音频管理
    // ----------------------
    @RequireRole("ADMIN")
    @GetMapping("/audios/full")
    public Object listAllAudios(@RequestParam(required = false) String username,
                                @RequestParam(required = false) String name) {
        return audioService.listFull(username, name);
    }

    @RequireRole("ADMIN")
    @DeleteMapping("/audio/{id}")
    public Object deleteAudio(@PathVariable Long id) {
        List<AudioRecord> records = audioService.listAll();
        for (AudioRecord record : records) {
            if (record.getId().equals(id)) {
                File file = (record.getUserId() != null && record.getUserId() == 0L
                        ? storagePaths.resolveAdminAudio(record.getFilePath())
                        : storagePaths.resolveAudio(record.getFilePath())).toFile();
                if (file.exists()) file.delete();
                audioService.delete(id);
                return "删除成功";
            }
        }
        return "Audio not found";
    }

    @RequireRole("ADMIN")
    @GetMapping("/audio/file/{filename}")
    public void streamUserAudioForAdmin(@PathVariable String filename,
                                        HttpServletResponse response) throws Exception {
        File file;
        try {
            file = storagePaths.resolveAudio(filename).toFile();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid audio filename");
        }
        if (!file.isFile()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio file not found");
        }
        response.setContentType(AudioMediaTypes.detect(file.toPath()));
        response.setContentLengthLong(file.length());
        response.setHeader("Content-Disposition", "inline; filename=\"" + file.getName() + "\"");
        try (FileInputStream input = new FileInputStream(file)) {
            input.transferTo(response.getOutputStream());
        }
    }

    @RequireRole("ADMIN")
    @PostMapping("/audios/upload")
    public Object uploadAdminAudios(@RequestParam("files") MultipartFile[] files) {

        List<String> savedFiles = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            try {
                String originalName = file.getOriginalFilename();
                if (originalName == null || originalName.isBlank()) continue;
                String fileName = Path.of(originalName).getFileName().toString();
                File dest = resolveAdminAudio(fileName);
                dest.getParentFile().mkdirs();
                file.transferTo(dest);
                audioService.save(0L, fileName, 0, "管理员"); // admin上传，userId=0
                savedFiles.add(fileName);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return savedFiles.isEmpty() ? "没有上传文件" : savedFiles;
    }

    @RequireRole("ADMIN")
    @GetMapping("/audios/admin")
    public Object listAdminAudios() {
        File folder = storagePaths.adminAudioDirectory().toFile();
        if (!folder.exists() || !folder.isDirectory()) return new ArrayList<>();
        List<String> files = new ArrayList<>();
        for (File f : folder.listFiles()) {
            if (f.isFile()) files.add(f.getName());
        }
        return files;
    }

    @RequireRole("ADMIN")
    @DeleteMapping("/audios/admin/{filename}")
    public Object deleteAdminAudio(@PathVariable String filename) {
        File file = resolveAdminAudio(filename);
        if (file.exists()) {
            file.delete();
            return "删除成功";
        } else return "文件不存在";
    }

    // ----------------------
    // 检测
    // ----------------------
    @GetMapping("/audios/admin/file/{filename}")
    public void streamAdminAudio(@PathVariable String filename,
                                 HttpServletResponse response) throws Exception {
        File file = resolveAdminAudio(filename);
        if (!file.isFile()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "音频不存在");
        }
        response.setContentType(AudioMediaTypes.detect(file.toPath()));
        response.setContentLengthLong(file.length());
        response.setHeader("Content-Disposition", "inline; filename=\"" + file.getName() + "\"");
        try (FileInputStream input = new FileInputStream(file)) {
            input.transferTo(response.getOutputStream());
        }
    }

    @RequireRole("ADMIN")
    @PostMapping("/detect")
    public Map<String,Object> adminDetect(@RequestBody Map<String,String> body,
                                          @RequestHeader("Authorization") String token) {

        String fileName = body.get("fileName");
        if (fileName == null || fileName.isEmpty())
            throw new RuntimeException("文件名不能为空");

        String fullPath = resolveAdminAudio(fileName).getAbsolutePath();
        RestTemplate restTemplate = new RestTemplate();
        MultiValueMap<String,Object> map = new LinkedMultiValueMap<>();
        map.add("file", new FileSystemResource(fullPath));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String,Object>> request = new HttpEntity<>(map, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "http://fastapi:8000/detect",
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

    // ----------------------
    // 踢人
    // ----------------------
    @RequireRole("ADMIN")
    @PostMapping("/kick")
    public String kick(@RequestParam String username) {
        String token = stringRedisTemplate.opsForValue().get("USER_TOKEN:" + username);
        if (token != null) {
            stringRedisTemplate.delete("TOKEN:" + token);
            stringRedisTemplate.delete("USER_TOKEN:" + username);
        }
        return "已踢下线";
    }

    // ----------------------
    // 系统统计
    // ----------------------
    @RequireRole("ADMIN")
    @GetMapping("/stats")
    public Object stats() {
        Map<String,Object> map = new HashMap<>();
        String total = stringRedisTemplate.opsForValue().get("DETECT_TOTAL");
        map.put("total", total == null ? 0 : total);
        return map;
    }

    private File resolveAdminAudio(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid audio filename");
        }
        try {
            return storagePaths.resolveAdminAudio(filename).toFile();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid audio filename");
        }
    }

}
