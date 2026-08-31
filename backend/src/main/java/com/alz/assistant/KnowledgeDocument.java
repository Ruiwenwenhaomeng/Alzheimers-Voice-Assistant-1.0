package com.alz.assistant;

import com.alz.dto.AssistantSource;

import java.util.List;

public record KnowledgeDocument(
        String id,
        AdQuestionCategory category,
        String question,
        String answer,
        List<String> keywords,
        List<String> actionSuggestions,
        List<AssistantSource> sources
) {
}
