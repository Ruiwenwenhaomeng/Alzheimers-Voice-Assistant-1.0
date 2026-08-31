package com.alz.entity;

public class UserFullInfo {

    private Long id;
    private String username;
    private String role;

    private String name;
    private String gender;
    private Integer age;
    private String phone;
    private String medicalHistory;
    private Integer mmse;
    private Integer moca;
    private Integer hkbc;

    // 无参构造
    public UserFullInfo() {
    }

    // 全参构造
    public UserFullInfo(Long id, String username, String role,
                        String name, String gender, Integer age,
                        String phone, String history,
                        Integer mmse, Integer moca, Integer hkbc) {
        this.id = id;
        this.username = username;
        this.role = role;
        this.name = name;
        this.gender = gender;
        this.age = age;
        this.phone = phone;
        this.medicalHistory = history;
        this.mmse = mmse;
        this.moca = moca;
        this.hkbc = hkbc;
    }

    // ===== Getter 和 Setter =====

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getMedicalHistory() {
        return medicalHistory;
    }

    public void setMedicalHistory(String medicalHistory) {
        this.medicalHistory = medicalHistory;
    }

    public Integer getMmse() {
        return mmse;
    }

    public void setMmse(Integer mmse) {
        this.mmse = mmse;
    }

    public Integer getMoca() {
        return moca;
    }

    public void setMoca(Integer moca) {
        this.moca = moca;
    }

    public Integer getHkbc() {
        return hkbc;
    }

    public void setHkbc(Integer hkbc) {
        this.hkbc = hkbc;
    }

    @Override
    public String toString() {
        return "UserFullInfo{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", role='" + role + '\'' +
                ", name='" + name + '\'' +
                ", gender='" + gender + '\'' +
                ", age=" + age +
                ", phone='" + phone + '\'' +
                ", medicalHistory='" + medicalHistory + '\'' +
                ", mmse=" + mmse +
                ", moca=" + moca +
                ", hkbc=" + hkbc +
                '}';
    }
}