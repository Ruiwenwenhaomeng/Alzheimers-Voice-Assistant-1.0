package com.alz.assistant;

import com.alz.dto.AssistantModelSettings;

import java.util.Locale;
import java.util.regex.Pattern;

public record LlmRequestConfig(LlmProvider provider, String model, String apiKey) {

    private static final Pattern MODEL_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}");

    public LlmRequestConfig {
        if (provider == null) throw new IllegalArgumentException("请选择大模型服务商");
        model = model == null || model.isBlank() ? provider.defaultModel() : model.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        if (!MODEL_PATTERN.matcher(model).matches()) {
            throw new IllegalArgumentException("模型名称格式无效");
        }
        if (apiKey.isBlank()) throw new IllegalArgumentException("请输入所选模型的 API Key");
        if (apiKey.length() > 1024) throw new IllegalArgumentException("API Key 长度不能超过1024个字符");
    }

    public static LlmRequestConfig from(AssistantModelSettings settings) {
        if (settings == null || settings.provider() == null
                || settings.provider().isBlank() || "SYSTEM".equalsIgnoreCase(settings.provider())) {
            return null;
        }
        LlmProvider provider;
        try {
            provider = LlmProvider.valueOf(settings.provider().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的大模型服务商");
        }
        return new LlmRequestConfig(provider, settings.model(), settings.apiKey());
    }

    @Override
    public String toString() {
        return "LlmRequestConfig[provider=%s, model=%s, apiKey=***]".formatted(provider, model);
    }
}
