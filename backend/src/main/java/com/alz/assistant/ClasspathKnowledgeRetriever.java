package com.alz.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ClasspathKnowledgeRetriever implements KnowledgeRetriever {

    private final List<KnowledgeDocument> documents;
    private final int minScore;

    @Autowired
    public ClasspathKnowledgeRetriever(
            ObjectMapper objectMapper,
            @Value("${app.rag.local.min-score:16}") int minScore) {
        this.minScore = Math.max(11, minScore);
        try {
            this.documents = List.copyOf(objectMapper.readValue(
                    new ClassPathResource("knowledge/ad-faq.json").getInputStream(),
                    new TypeReference<List<KnowledgeDocument>>() { }
            ));
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载阿尔茨海默病问答知识库", exception);
        }
    }

    public ClasspathKnowledgeRetriever(ObjectMapper objectMapper) {
        this(objectMapper, 16);
    }

    @Override
    public List<KnowledgeDocument> retrieve(String question, AdQuestionCategory category, int limit) {
        if (limit <= 0 || question == null || category == null) {
            return List.of();
        }
        String normalizedQuestion = normalize(question);
        Set<String> questionBigrams = bigrams(normalizedQuestion);
        List<ScoredDocument> scored = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            if (document.category() != category) {
                continue;
            }
            int score = 10;
            String normalizedStoredQuestion = normalize(document.question());
            if (normalizedQuestion.contains(normalizedStoredQuestion) || normalizedStoredQuestion.contains(normalizedQuestion)) {
                score += 30;
            }
            for (String keyword : document.keywords()) {
                if (normalizedQuestion.contains(normalize(keyword))) {
                    score += 8;
                }
            }
            Set<String> overlap = new HashSet<>(questionBigrams);
            overlap.retainAll(bigrams(normalizedStoredQuestion));
            score += Math.min(20, overlap.size() * 2);
            scored.add(new ScoredDocument(document, score));
        }
        return scored.stream()
                .filter(item -> item.score() >= minScore)
                .sorted(Comparator.comparingInt(ScoredDocument::score).reversed()
                        .thenComparing(item -> item.document().id()))
                .limit(limit)
                .map(ScoredDocument::document)
                .toList();
    }

    public List<KnowledgeDocument> allDocuments() {
        return documents;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？、；：,.!?;:]", "");
    }

    private static Set<String> bigrams(String value) {
        Set<String> result = new HashSet<>();
        for (int index = 0; index + 1 < value.length(); index++) {
            result.add(value.substring(index, index + 2));
        }
        return result;
    }

    private record ScoredDocument(KnowledgeDocument document, int score) { }
}
