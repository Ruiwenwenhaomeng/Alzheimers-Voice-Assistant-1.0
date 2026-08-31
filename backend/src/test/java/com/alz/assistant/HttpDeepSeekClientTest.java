package com.alz.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

class HttpDeepSeekClientTest {

    @Test
    void streamsOnlyVisibleContentAndDropsReasoning() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://deepseek.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDeepSeekClient client = new HttpDeepSeekClient(
                builder.build(),
                RestClient.builder().baseUrl("http://deepseek.test/anthropic").build(),
                new ObjectMapper(), true, false, "test-key", "test-model", 700, 3);
        server.expect(requestTo("http://deepseek.test/chat/completions"))
                .andRespond(withSuccess("""
                        data: {"choices":[{"delta":{"reasoning_content":"绝不能显示"}}]}

                        data: {"choices":[{"delta":{"content":"答<th"}}]}

                        data: {"choices":[{"delta":{"content":"ink>内部推理</think>案"}}]}

                        data: [DONE]

                        """, MediaType.TEXT_EVENT_STREAM));
        AdQuestionRoute route = new AdQuestionRoute(
                AdQuestionCategory.INTRODUCTION, 0.8, false, List.of(), "test");
        KnowledgeDocument document = new KnowledgeDocument(
                "K01", AdQuestionCategory.INTRODUCTION, "问题", "答案",
                List.of(), List.of(), List.of());
        StringBuilder deltas = new StringBuilder();

        var result = client.streamAnswer("测试", route, List.of(document),
                ConversationContext.empty(), deltas::append);

        assertEquals("答案", result.orElseThrow());
        assertEquals("答案", deltas.toString());
        server.verify();
    }

    @Test
    void usesAnthropicWebSearchAndExtractsSources() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://deepseek.test/anthropic");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDeepSeekClient client = new HttpDeepSeekClient(
                RestClient.builder().baseUrl("http://deepseek.test").build(),
                builder.build(),
                new ObjectMapper(),
                true,
                true,
                "test-key",
                "deepseek-v4-flash",
                700,
                3
        );
        server.expect(requestTo("http://deepseek.test/anthropic/v1/messages"))
                .andRespond(withSuccess("""
                        {
                          "stop_reason":"end_turn",
                          "content":[
                            {"type":"server_tool_use","name":"web_search"},
                            {"type":"web_search_tool_result","content":[
                              {"type":"web_search_result","title":"世界卫生组织","url":"https://www.who.int/example"}
                            ]},
                            {"type":"text","text":"联网回答"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        AdQuestionRoute route = new AdQuestionRoute(
                AdQuestionCategory.INTRODUCTION, 0.8, false, List.of(), "test");
        var result = client.answerWithWebSearch("测试问题", route);

        assertTrue(result.isPresent());
        assertEquals("联网回答", result.orElseThrow().content());
        assertEquals("https://www.who.int/example", result.orElseThrow().sources().get(0).url());
        server.verify();
    }

    @Test
    void streamsWebSearchSourcesBeforeAnswerDeltas() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://deepseek.test/anthropic");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDeepSeekClient client = new HttpDeepSeekClient(
                RestClient.builder().baseUrl("http://deepseek.test").build(),
                builder.build(),
                new ObjectMapper(), true, true, "test-key", "deepseek-v4-flash", 700, 3);
        server.expect(requestTo("http://deepseek.test/anthropic/v1/messages"))
                .andRespond(withSuccess("""
                        event: message_start
                        data: {"type":"message_start","message":{"role":"assistant"}}

                        event: content_block_start
                        data: {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","name":"web_search"}}

                        event: content_block_start
                        data: {"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","content":[{"type":"web_search_result","title":"世界卫生组织","url":"https://www.who.int/example"}]}}

                        event: content_block_start
                        data: {"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"联网"}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"回答"}}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """, MediaType.TEXT_EVENT_STREAM));
        AdQuestionRoute route = new AdQuestionRoute(
                AdQuestionCategory.INTRODUCTION, 0.8, false, List.of(), "test");
        List<String> eventOrder = new ArrayList<>();
        StringBuilder deltas = new StringBuilder();

        var result = client.streamAnswerWithWebSearch("测试问题", route, new AssistantStreamListener() {
            @Override
            public void onSource(com.alz.dto.AssistantSource source) {
                eventOrder.add("source:" + source.title());
            }

            @Override
            public void onAnswerDelta(String content) {
                eventOrder.add("delta:" + content);
                deltas.append(content);
            }
        });

        assertEquals("联网回答", result.orElseThrow().content());
        assertEquals("https://www.who.int/example", result.orElseThrow().sources().get(0).url());
        assertEquals("联网回答", deltas.toString());
        assertTrue(eventOrder.indexOf("source:世界卫生组织") < eventOrder.indexOf("delta:联网"));
        server.verify();
    }

    @Test
    void routesUserSelectedKimiModelWithItsOwnApiKey() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://deepseek.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDeepSeekClient client = new HttpDeepSeekClient(
                builder.build(),
                RestClient.builder().baseUrl("http://deepseek.test/anthropic").build(),
                new ObjectMapper(), false, false, "", "server-model", 700, 3);
        server.expect(requestTo("http://deepseek.test/chat/completions"))
                .andExpect(header("Authorization", "Bearer kimi-user-key"))
                .andExpect(content().string(containsString("\"model\":\"kimi-k2.6\"")))
                .andExpect(content().string(not(containsString("\"thinking\""))))
                .andRespond(withSuccess("""
                        data: {"choices":[{"delta":{"content":"Kimi回答"}}]}

                        data: [DONE]

                        """, MediaType.TEXT_EVENT_STREAM));
        AdQuestionRoute route = new AdQuestionRoute(
                AdQuestionCategory.INTRODUCTION, 0.8, false, List.of(), "test");
        KnowledgeDocument document = new KnowledgeDocument(
                "K01", AdQuestionCategory.INTRODUCTION, "问题", "答案",
                List.of(), List.of(), List.of());
        StringBuilder deltas = new StringBuilder();

        var result = client.streamAnswer(
                "测试", route, List.of(document), ConversationContext.empty(),
                new LlmRequestConfig(LlmProvider.KIMI, "kimi-k2.6", "kimi-user-key"),
                deltas::append);

        assertEquals("Kimi回答", result.orElseThrow());
        assertEquals("Kimi回答", deltas.toString());
        server.verify();
    }
}
