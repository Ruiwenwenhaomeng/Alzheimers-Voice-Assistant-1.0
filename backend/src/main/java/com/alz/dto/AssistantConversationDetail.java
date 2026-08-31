package com.alz.dto;

import java.util.List;

public record AssistantConversationDetail(
        AssistantConversationSummary conversation,
        List<AssistantMessageView> messages
) {
}
