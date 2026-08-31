package com.alz.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class HttpEmbeddingClientTest {

    @Test
    void callsOpenAiCompatibleEmbeddingEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://embedding.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEmbeddingClient client = new HttpEmbeddingClient(
                builder.build(), true, "", "BAAI/bge-m3");
        server.expect(requestTo("http://embedding.test/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"data":[{"index":0,"embedding":[0.1,0.2]}]}
                        """, MediaType.APPLICATION_JSON));

        var result = client.embed("测试问题");

        assertTrue(result.isPresent());
        assertEquals(List.of(0.1, 0.2), result.orElseThrow());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 64, 65, 300})
    void splitsRequestsAtSixtyFourAndPreservesVectorOrder(int textCount) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://embedding.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEmbeddingClient client = new HttpEmbeddingClient(
                builder.build(), true, "", "BAAI/bge-m3");

        for (int from = 0; from < textCount; from += HttpEmbeddingClient.MAX_BATCH_SIZE) {
            int batchSize = Math.min(HttpEmbeddingClient.MAX_BATCH_SIZE, textCount - from);
            server.expect(requestTo("http://embedding.test/v1/embeddings"))
                    .andExpect(jsonPath("$.input.length()").value(batchSize))
                    .andRespond(withSuccess(embeddingResponse(from, batchSize), MediaType.APPLICATION_JSON));
        }

        var result = client.embedAll(texts(textCount));

        assertTrue(result.isPresent());
        assertEquals(textCount, result.orElseThrow().size());
        for (int index = 0; index < textCount; index++) {
            assertEquals(List.of((double) index), result.orElseThrow().get(index));
        }
        server.verify();
    }

    @Test
    void returnsEmptyWhenSecondBatchFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://embedding.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEmbeddingClient client = new HttpEmbeddingClient(
                builder.build(), true, "", "BAAI/bge-m3");
        server.expect(requestTo("http://embedding.test/v1/embeddings"))
                .andExpect(jsonPath("$.input.length()").value(64))
                .andRespond(withSuccess(embeddingResponse(0, 64), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://embedding.test/v1/embeddings"))
                .andExpect(jsonPath("$.input.length()").value(1))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertTrue(client.embedAll(texts(65)).isEmpty());
        server.verify();
    }

    private static List<String> texts(int count) {
        return IntStream.range(0, count).mapToObj(index -> "文本-" + index).toList();
    }

    private static String embeddingResponse(int offset, int batchSize) {
        StringBuilder json = new StringBuilder("{\"data\":[");
        for (int index = batchSize - 1; index >= 0; index--) {
            if (index < batchSize - 1) {
                json.append(',');
            }
            json.append("{\"index\":").append(index)
                    .append(",\"embedding\":[").append(offset + index).append(".0]}");
        }
        return json.append("]}").toString();
    }
}
