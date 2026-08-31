package com.alz.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QdrantKnowledgeRetrieverTest {

    @Test
    void warmUpBuildsVersionedIndexInBatchesBeforeFirstQuestion() {
        ObjectMapper objectMapper = new ObjectMapper();
        ClasspathKnowledgeRetriever fallback = new ClasspathKnowledgeRetriever(objectMapper);
        assertEquals(300, fallback.allDocuments().size());

        RestClient.Builder builder = RestClient.builder().baseUrl("http://qdrant.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantKnowledgeRetriever retriever = new QdrantKnowledgeRetriever(
                fallback,
                new FixedEmbeddingClient(),
                builder,
                objectMapper,
                true,
                "http://qdrant.test",
                "",
                "alz_ad_knowledge",
                0.72,
                true,
                true,
                "BAAI/bge-m3"
        );

        server.expect(requestTo("http://qdrant.test/collections/alz_ad_knowledge"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://qdrant.test/collections/alz_ad_knowledge"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://qdrant.test/collections/alz_ad_knowledge"))
                .andExpect(method(org.springframework.http.HttpMethod.PUT))
                .andRespond(withSuccess());
        for (int batchSize : List.of(64, 64, 64, 64, 44)) {
            server.expect(requestTo("http://qdrant.test/collections/alz_ad_knowledge/points?wait=true"))
                    .andExpect(jsonPath("$.points.length()").value(batchSize))
                    .andExpect(jsonPath("$.points[0].payload._indexVersion").value(retriever.indexVersion()))
                    .andRespond(withSuccess());
        }
        server.expect(requestTo("http://qdrant.test/collections/alz_ad_knowledge"))
                .andRespond(withSuccess("""
                        {"result":{"status":"green","points_count":300}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/alz_ad_knowledge/index?wait=true"))
                .andExpect(jsonPath("$.field_name").value("category"))
                .andRespond(withSuccess());
        assertTrue(retriever.warmUp());
        server.verify();
    }

    @Test
    void warmUpSkipsEmbeddingWhenStoredIndexVersionMatches() {
        ObjectMapper objectMapper = new ObjectMapper();
        ClasspathKnowledgeRetriever fallback = new ClasspathKnowledgeRetriever(objectMapper);
        FixedEmbeddingClient embeddingClient = new FixedEmbeddingClient();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://qdrant.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantKnowledgeRetriever retriever = new QdrantKnowledgeRetriever(
                fallback, embeddingClient, builder, objectMapper, true,
                "http://qdrant.test", "", "alz_ad_knowledge", 0.72,
                true, true, "BAAI/bge-m3"
        );
        String firstPointId = UUID.nameUUIDFromBytes(
                fallback.allDocuments().get(0).id().getBytes(StandardCharsets.UTF_8)).toString();

        server.expect(requestTo("http://qdrant.test/collections/alz_ad_knowledge"))
                .andRespond(withSuccess());
        server.expect(requestTo("http://qdrant.test/collections/alz_ad_knowledge"))
                .andRespond(withSuccess("""
                        {"result":{"status":"green","points_count":300}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/alz_ad_knowledge/points/" + firstPointId))
                .andRespond(withSuccess("""
                        {"result":{"payload":{"_indexVersion":"%s"}}}
                        """.formatted(retriever.indexVersion()), MediaType.APPLICATION_JSON));

        assertTrue(retriever.warmUp());
        assertEquals(0, embeddingClient.calls);
        server.verify();
    }

    private static final class FixedEmbeddingClient implements EmbeddingClient {
        private int calls;

        @Override
        public Optional<List<List<Double>>> embedAll(List<String> texts) {
            calls++;
            return Optional.of(texts.stream().map(ignored -> List.of(1.0, 0.0)).toList());
        }
    }
}
