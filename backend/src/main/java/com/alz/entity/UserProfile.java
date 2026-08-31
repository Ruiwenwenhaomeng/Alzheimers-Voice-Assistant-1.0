package com.alz.entity;

import java.time.LocalDateTime;

public class UserProfile {

    private Long id;
    private Long userId;

    private String name;
    private String gender;
    private Integer age;
    private String phone;
    private String medicalHistory; // 个人病史

    private Integer mmse;  // MMSE量表分数，只读
    private Integer moca;  // MoCA量表分数，只读
    private Integer hkbc;  // HKBC量表分数，只读

    private LocalDateTime updateTime;

    // ===== Getters =====
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public String getGender() { return gender; }
    public Integer getAge() { return age; }
    public String getPhone() { return phone; }
    public String getMedicalHistory() { return medicalHistory; }
    public Integer getMmse() { return mmse; }
    public Integer getMoca() { return moca; }
    public Integer getHkbc() { return hkbc; }
    public LocalDateTime getUpdateTime() { return updateTime; }

    // ===== Setters =====
    public void setId(Long id) { this.id = id; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setName(String name) { this.name = name; }
    public void setGender(String gender) { this.gender = gender; }
    public void setAge(Integer age) { this.age = age; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setMedicalHistory(String medicalHistory) { this.medicalHistory = medicalHistory; }

    // 注意量表分数只读，setter可以保留给后台使用
    public void setMmse(Integer mmse) { this.mmse = mmse; }
    public void setMoca(Integer moca) { this.moca = moca; }
    public void setHkbc(Integer hkbc) { this.hkbc = hkbc; }

    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}