package com.alz.service;

import com.alz.assistant.AdQuestionRouter;
import com.alz.assistant.AssistantStreamListener;
import com.alz.assistant.ClasspathKnowledgeRetriever;
import com.alz.assistant.DeepSeekClient;
import com.alz.assistant.KeywordAdQuestionRouter;
import com.alz.assistant.KnowledgeRetriever;
import com.alz.assistant.LlmProvider;
import com.alz.assistant.LlmRequestConfig;
import com.alz.assistant.WebSearchAnswer;
import com.alz.dto.AssistantChatResponse;
import com.alz.dto.AssistantSource;
import com.alz.service.impl.AssistantServiceImpl;
import com.alz.service.impl.RagAssistantServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagAssistantServiceImplTest {

    private final AdQuestionRouter router = new KeywordAdQuestionRouter();
    private final KnowledgeRetriever retriever = new ClasspathKnowledgeRetriever(new ObjectMapper());

    @Test
    void usesLocalRagAnswerWhenDeepSeekIsUnavailable() {
        DeepSeekClient unavailableClient = (question, route, knowledge) -> Optional.empty();
        AssistantService service = new RagAssistantServiceImpl(
                router, retriever, unavailableClient, new AssistantServiceImpl(), 4);

        AssistantChatResponse response = service.chat("什么是阿尔茨海默病？");

        assertEquals("rag_introduction", response.intent());
        assertTrue(response.answer().contains("脑部疾病"));
        assertFalse(response.sources().isEmpty());
    }

    @Test
    void usesGroundedModelAnswerWhenClientSucceeds() {
        DeepSeekClient client = (question, route, knowledge) -> Optional.of("这是基于检索资料生成的回答[K01]");
        AssistantService service = new RagAssistantServiceImpl(
                router, retriever, client, new AssistantServiceImpl(), 4);

        AssistantChatResponse response = service.chat("阿尔茨海默病是什么？");

        assertEquals("这是基于检索资料生成的回答[K01]", response.answer());
    }

    @Test
    void doesNotSendEmergencyQuestionToModel() {
        DeepSeekClient failingClient = (question, route, knowledge) -> {
            throw new AssertionError("急症问题不应发送给模型");
        };
        AssistantService service = new RagAssistantServiceImpl(
                router, retriever, failingClient, new AssistantServiceImpl(), 4);

        AssistantChatResponse response = service.chat("老人突然说不清话而且一侧无力");

        assertEquals("emergency", response.intent());
        assertTrue(response.urgent());
        assertTrue(response.answer().contains("120"));
    }

    @Test
    void exposesOnlyThreeMainKnowledgeDomains() {
        AssistantService service = new RagAssistantServiceImpl(
                router, retriever, (question, route, knowledge) -> Optional.empty(), new AssistantServiceImpl(), 4);

        assertEquals(List.of("疾病介绍", "常见症状", "应对方法"), service.supportedTopics());
    }

    @Test
    void usesWebSearchWhenVectorKnowledgeHasNoConfidentMatch() {
        KnowledgeRetriever noMatch = (question, category, limit) -> List.of();
        DeepSeekClient client = new DeepSeekClient() {
            @Override
            public Optional<String> answer(String question, com.alz.assistant.AdQuestionRoute route,
                                           List<com.alz.assistant.KnowledgeDocument> knowledge) {
                throw new AssertionError("没有知识命中时不应调用本地 RAG 生成");
            }

            @Override
            public Optional<WebSearchAnswer> answerWithWebSearch(
                    String question, com.alz.assistant.AdQuestionRoute route) {
                return Optional.of(new WebSearchAnswer(
                        "这是联网资料辅助生成的回答。",
                        List.of(new AssistantSource("权威来源", "https://example.org/ad"))
                ));
            }
        };
        AssistantService service = new RagAssistantServiceImpl(
                router, noMatch, client, new AssistantServiceImpl(), 4);

        AssistantChatResponse response = service.chat("阿尔茨海默病最近有什么新的照护建议？");

        assertEquals("web_coping", response.intent());
        assertTrue(response.answer().contains("谨慎甄别"));
        assertEquals("https://example.org/ad", response.sources().get(0).url());
    }

    @Test
    void refusesToInventAnswerWhenKnowledgeAndWebSearchAreUnavailable() {
        KnowledgeRetriever noMatch = (question, category, limit) -> List.of();
        AssistantService service = new RagAssistantServiceImpl(
                router, noMatch, (question, route, knowledge) -> Optional.empty(),
                new AssistantServiceImpl(), 4);

        AssistantChatResponse response = service.chat("阿尔茨海默病最近有什么新的照护建议？");

        assertEquals("knowledge_unavailable", response.intent());
        assertTrue(response.answer().contains("不能回答"));
    }

    @Test
    void streamsWebSearchProgressSourcesAndAnswerSeparately() {
        KnowledgeRetriever noMatch = (question, category, limit) -> List.of();
        DeepSeekClient client = new DeepSeekClient() {
            @Override
            public Optional<String> answer(String question, com.alz.assistant.AdQuestionRoute route,
                                           List<com.alz.assistant.KnowledgeDocument> knowledge) {
                return Optional.empty();
            }

            @Override
            public Optional<WebSearchAnswer> streamAnswerWithWebSearch(
                    String question, com.alz.assistant.AdQuestionRoute route,
                    AssistantStreamListener listener) {
                AssistantSource source = new AssistantSource("权威来源", "https://example.org/ad");
                listener.onSource(source);
                listener.onAnswerDelta("联网回答");
                return Optional.of(new WebSearchAnswer("联网回答", List.of(source)));
            }
        };
        RagAssistantServiceImpl service = new RagAssistantServiceImpl(
                router, noMatch, client, new AssistantServiceImpl(), 4);
        List<String> statuses = new ArrayList<>();
        List<String> analyses = new ArrayList<>();
        List<AssistantSource> sources = new ArrayList<>();
        StringBuilder answer = new StringBuilder();

        AssistantChatResponse response = service.streamChat(
                "阿尔茨海默病最近有什么新的照护建议？",
                com.alz.assistant.ConversationContext.empty(),
                new AssistantStreamListener() {
                    @Override public void onStatus(String message) { statuses.add(message); }
                    @Override public void onAnalysis(String summary) { analyses.add(summary); }
                    @Override public void onSource(AssistantSource source) { sources.add(source); }
                    @Override public void onAnswerDelta(String content) { answer.append(content); }
                });

        assertEquals("web_coping", response.intent());
        assertTrue(statuses.stream().anyMatch(value -> value.contains("联网检索")));
        assertTrue(analyses.stream().anyMatch(value -> value.contains("本地知识库")));
        assertEquals("https://example.org/ad", sources.get(0).url());
        assertTrue(answer.toString().startsWith("联网回答"));
        assertTrue(answer.toString().contains("谨慎甄别"));
    }

    @Test
    void forwardsTheCurrentUserModelSelectionToTheClient() {
        AtomicReference<LlmRequestConfig> receivedConfig = new AtomicReference<>();
        DeepSeekClient client = new DeepSeekClient() {
            @Override
            public Optional<String> answer(String question, com.alz.assistant.AdQuestionRoute route,
                                           List<com.alz.assistant.KnowledgeDocument> knowledge) {
                return Optional.empty();
            }

            @Override
            public Optional<String> streamAnswer(
                    String question, com.alz.assistant.AdQuestionRoute route,
                    List<com.alz.assistant.KnowledgeDocument> knowledge,
                    com.alz.assistant.ConversationContext memory,
                    LlmRequestConfig config, Consumer<String> onDelta) {
                receivedConfig.set(config);
                onDelta.accept("千问回答");
                return Optional.of("千问回答");
            }
        };
        RagAssistantServiceImpl service = new RagAssistantServiceImpl(
                router, retriever, client, new AssistantServiceImpl(), 4);
        LlmRequestConfig config = new LlmRequestConfig(
                LlmProvider.QWEN, "qwen-plus", "user-key");

        AssistantChatResponse response = service.streamChat(
                "什么是阿尔茨海默病？", com.alz.assistant.ConversationContext.empty(),
                config, new AssistantStreamListener() { });

        assertEquals("千问回答", response.answer());
        assertEquals(LlmProvider.QWEN, receivedConfig.get().provider());
        assertEquals("qwen-plus", receivedConfig.get().model());
    }
}
