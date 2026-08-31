package com.alz.assistant;

public enum AdQuestionCategory {
    INTRODUCTION("疾病介绍"),
    SYMPTOMS("常见症状"),
    COPING("应对方法"),
    EMERGENCY("急症处理"),
    OUT_OF_SCOPE("超出范围");

    private final String displayName;

    AdQuestionCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
