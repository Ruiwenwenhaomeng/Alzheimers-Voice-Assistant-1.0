package com.alz.dto;

import java.util.List;

public record AssistantChatResponse(
        String intent,
        String title,
        String answer,
        List<String> actionSuggestions,
        String medicalDisclaimer,
        List<AssistantSource> sources,
        boolean urgent
) {
}
