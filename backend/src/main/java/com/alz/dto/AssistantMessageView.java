package com.alz.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AssistantMessageView(
        long id,
        int turnNo,
        String role,
        String content,
        String title,
        String intent,
        boolean urgent,
        List<String> actionSuggestions,
        String medicalDisclaimer,
        List<AssistantSource> sources,
        LocalDateTime createdAt
) {
}
