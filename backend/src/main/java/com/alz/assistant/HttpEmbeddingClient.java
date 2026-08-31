package com.alz.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpEmbeddingClient.class);
    static final int MAX_BATCH_SIZE = 64;

    private final RestClient restClient;
    private final boolean enabled;
    private final String apiKey;
    private final String model;

    @Autowired
    public HttpEmbeddingClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.rag.embedding.enabled:false}") boolean enabled,
            @Value("${app.rag.embedding.base-url:http://127.0.0.1:7997/v1}") String baseUrl,
            @Value("${app.rag.embedding.api-key:}") String apiKey,
            @Value("${app.rag.embedding.model:BAAI/bge-m3}") String model,
            @Value("${app.rag.embedding.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${app.rag.embedding.read-timeout-ms:30000}") int readTimeoutMs
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    HttpEmbeddingClient(RestClient restClient, boolean enabled, String apiKey, String model) {
        this.restClient = restClient;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    @Override
    public Optional<List<List<Double>>> embedAll(List<String> texts) {
        if (!enabled || texts == null || texts.isEmpty()
                || texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            return Optional.empty();
        }
        try {
            List<List<Double>> result = new ArrayList<>(texts.size());
            for (int from = 0; from < texts.size(); from += MAX_BATCH_SIZE) {
                int to = Math.min(from + MAX_BATCH_SIZE, texts.size());
                Optional<List<List<Double>>> vectors = embedBatch(texts.subList(from, to));
                if (vectors.isEmpty()) {
                    return Optional.empty();
                }
                result.addAll(vectors.orElseThrow());
            }

            return result.size() == texts.size()
                    ? Optional.of(List.copyOf(result))
                    : Optional.empty();
        } catch (RuntimeException exception) {
            LOGGER.warn("Embedding 服务调用失败，向量检索将降级: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<List<List<Double>>> embedBatch(List<String> texts) {
        RestClient.RequestBodySpec request = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON);
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        EmbeddingResponse response = request
                .body(Map.of("model", model, "input", texts))
                .retrieve()
                .body(EmbeddingResponse.class);
        if (response == null || response.data() == null || response.data().size() != texts.size()) {
            return Optional.empty();
        }
        List<EmbeddingData> orderedData = response.data().stream()
                .sorted(Comparator.comparingInt(item -> item.index() == null ? Integer.MAX_VALUE : item.index()))
                .toList();
        for (int index = 0; index < orderedData.size(); index++) {
            if (orderedData.get(index).index() == null || orderedData.get(index).index() != index) {
                return Optional.empty();
            }
        }
        List<List<Double>> vectors = orderedData.stream()
                .map(EmbeddingData::embedding)
                .toList();
        if (vectors.stream().anyMatch(vector -> vector == null || vector.isEmpty()
                || vector.stream().anyMatch(value -> value == null || !Double.isFinite(value)))) {
            return Optional.empty();
        }
        return Optional.of(vectors);
    }

    private record EmbeddingResponse(List<EmbeddingData> data) { }

    private record EmbeddingData(Integer index, List<Double> embedding) { }
}
