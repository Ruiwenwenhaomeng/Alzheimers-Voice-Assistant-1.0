package com.alz.entity;

import java.time.LocalDateTime;

public class AudioFullInfo {

    private Long id;
    private Long userId;
    private String userName;
    private String name;      // 个人信息里的名字
    private String fileName;
    private String filePath;
    private Integer duration;
    private LocalDateTime uploadTime;
    private String url; // getter/setter

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) {this.url = url; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String fileName) {
        this.filePath = "/files/" + fileName;
    }

    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }

    public LocalDateTime getUploadTime() { return uploadTime; }
    public void setUploadTime(LocalDateTime uploadTime) { this.uploadTime = uploadTime; }
}