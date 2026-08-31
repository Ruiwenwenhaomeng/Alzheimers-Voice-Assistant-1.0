package com.alz.dto;

import java.time.LocalDateTime;

public record AssistantConversationSummary(
        String id,
        String title,
        int turnCount,
        int maxTurns,
        int summarizedThroughTurn,
        boolean generating,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
