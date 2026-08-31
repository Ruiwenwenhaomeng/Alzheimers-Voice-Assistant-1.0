package com.alz.dto;

public record AssistantModelSettings(String provider, String model, String apiKey) {

    @Override
    public String toString() {
        return "AssistantModelSettings[provider=%s, model=%s, apiKey=***]".formatted(provider, model);
    }
}
