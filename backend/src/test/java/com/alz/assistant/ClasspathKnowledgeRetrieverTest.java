package com.alz.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathKnowledgeRetrieverTest {

    private final ClasspathKnowledgeRetriever retriever = new ClasspathKnowledgeRetriever(new ObjectMapper());

    @Test
    void knowledgeBaseContainsOneHundredQuestionsPerMainCategory() {
        assertEquals(100, retriever.allDocuments().stream()
                .filter(document -> document.category() == AdQuestionCategory.INTRODUCTION).count());
        assertEquals(100, retriever.allDocuments().stream()
                .filter(document -> document.category() == AdQuestionCategory.SYMPTOMS).count());
        assertEquals(100, retriever.allDocuments().stream()
                .filter(document -> document.category() == AdQuestionCategory.COPING).count());
    }

    @Test
    void retrievesWanderingKnowledgeFirst() {
        List<KnowledgeDocument> result = retriever.retrieve("患者总在熟悉的地方迷路", AdQuestionCategory.SYMPTOMS, 3);

        assertFalse(result.isEmpty());
        assertEquals("K14", result.get(0).id());
    }

    @Test
    void returnsNoKnowledgeWhenOnlyTheCategoryMatches() {
        List<KnowledgeDocument> result = retriever.retrieve(
                "2026年刚公布且知识库尚未收录的全新技术进展",
                AdQuestionCategory.COPING,
                4
        );

        assertTrue(result.isEmpty());
    }
}
