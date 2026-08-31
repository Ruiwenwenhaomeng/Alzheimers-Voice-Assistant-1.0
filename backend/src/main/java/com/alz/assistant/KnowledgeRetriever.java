package com.alz.assistant;

import java.util.List;

public interface KnowledgeRetriever {

    List<KnowledgeDocument> retrieve(String question, AdQuestionCategory category, int limit);
}
