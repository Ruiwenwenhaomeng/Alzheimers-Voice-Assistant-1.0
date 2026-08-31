package com.alz.assistant;

import com.alz.dto.AssistantSource;

import java.util.List;

public record WebSearchAnswer(
        String content,
        List<AssistantSource> sources
) {
}
