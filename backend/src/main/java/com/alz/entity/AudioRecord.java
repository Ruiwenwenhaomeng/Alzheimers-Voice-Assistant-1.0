package com.alz.entity;

import java.time.LocalDateTime;

public class AudioRecord {

    private Long id;
    private Long userId;
    private String filePath;
    private Integer duration;
    private LocalDateTime uploadTime;
    private String imageName;
    private String consentVersion;
    private LocalDateTime consentTime;
    private String taskType;

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getFilePath() {
        return filePath;
    }
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Integer getDuration() {
        return duration;
    }
    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public LocalDateTime getUploadTime() {
        return uploadTime;
    }
    public void setUploadTime(LocalDateTime uploadTime) {
        this.uploadTime = uploadTime;
    }

    public String getImageName() {
        return imageName;
    }
    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getConsentVersion() {
        return consentVersion;
    }

    public void setConsentVersion(String consentVersion) {
        this.consentVersion = consentVersion;
    }

    public LocalDateTime getConsentTime() {
        return consentTime;
    }

    public void setConsentTime(LocalDateTime consentTime) {
        this.consentTime = consentTime;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }
}
