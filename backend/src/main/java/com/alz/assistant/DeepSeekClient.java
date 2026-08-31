package com.alz.assistant;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface DeepSeekClient {

    Optional<String> answer(String question, AdQuestionRoute route, List<KnowledgeDocument> knowledge);

    default Optional<String> answer(String question, AdQuestionRoute route,
                                    List<KnowledgeDocument> knowledge, ConversationContext memory) {
        return answer(question, route, knowledge);
    }

    default Optional<String> answer(String question, AdQuestionRoute route,
                                    List<KnowledgeDocument> knowledge, ConversationContext memory,
                                    LlmRequestConfig config) {
        return answer(question, route, knowledge, memory);
    }

    default Optional<String> streamAnswer(String question, AdQuestionRoute route,
                                          List<KnowledgeDocument> knowledge, ConversationContext memory,
                                          Consumer<String> onDelta) {
        Optional<String> answer = answer(question, route, knowledge, memory)
                .map(ThinkContentFilter::strip)
                .filter(value -> !value.isBlank());
        answer.ifPresent(onDelta);
        return answer;
    }

    default Optional<String> streamAnswer(String question, AdQuestionRoute route,
                                          List<KnowledgeDocument> knowledge, ConversationContext memory,
                                          LlmRequestConfig config, Consumer<String> onDelta) {
        return streamAnswer(question, route, knowledge, memory, onDelta);
    }

    default Optional<String> summarize(String previousSummary, List<ConversationTurn> turns) {
        return Optional.empty();
    }

    default Optional<WebSearchAnswer> answerWithWebSearch(String question, AdQuestionRoute route) {
        return Optional.empty();
    }

    default Optional<WebSearchAnswer> answerWithWebSearch(
            String question, AdQuestionRoute route, LlmRequestConfig config) {
        if (config != null && config.provider() != LlmProvider.DEEPSEEK) {
            return Optional.empty();
        }
        return answerWithWebSearch(question, route);
    }

    default Optional<WebSearchAnswer> streamAnswerWithWebSearch(
            String question, AdQuestionRoute route, AssistantStreamListener listener) {
        Optional<WebSearchAnswer> answer = answerWithWebSearch(question, route);
        answer.ifPresent(value -> {
            value.sources().forEach(listener::onSource);
            listener.onAnswerDelta(value.content());
        });
        return answer;
    }

    default Optional<WebSearchAnswer> streamAnswerWithWebSearch(
            String question, AdQuestionRoute route, LlmRequestConfig config,
            AssistantStreamListener listener) {
        if (config != null && config.provider() != LlmProvider.DEEPSEEK) {
            return Optional.empty();
        }
        return streamAnswerWithWebSearch(question, route, listener);
    }
}
