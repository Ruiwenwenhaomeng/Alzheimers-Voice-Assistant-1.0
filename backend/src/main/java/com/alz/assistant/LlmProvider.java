package com.alz.assistant;

public enum LlmProvider {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
    KIMI("Kimi", "https://api.moonshot.cn/v1", "kimi-k2.6"),
    GLM("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-5.2"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus");

    private final String displayName;
    private final String baseUrl;
    private final String defaultModel;

    LlmProvider(String displayName, String baseUrl, String defaultModel) {
        this.displayName = displayName;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
    }

    public String displayName() {
        return displayName;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String defaultModel() {
        return defaultModel;
    }
}
