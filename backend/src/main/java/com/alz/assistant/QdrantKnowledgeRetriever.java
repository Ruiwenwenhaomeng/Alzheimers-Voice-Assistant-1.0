package com.alz.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Primary
public class QdrantKnowledgeRetriever implements KnowledgeRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(QdrantKnowledgeRetriever.class);
    static final int UPSERT_BATCH_SIZE = 64;
    static final String INDEX_VERSION_PAYLOAD_KEY = "_indexVersion";

    private final ClasspathKnowledgeRetriever fallbackRetriever;
    private final EmbeddingClient embeddingClient;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean bootstrap;
    private final boolean payloadIndexEnabled;
    private final String apiKey;
    private final String collection;
    private final String indexVersion;
    private final double scoreThreshold;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final Object initializationMonitor = new Object();

    public QdrantKnowledgeRetriever(
            ClasspathKnowledgeRetriever fallbackRetriever,
            EmbeddingClient embeddingClient,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${app.rag.vector.enabled:false}") boolean enabled,
            @Value("${app.rag.vector.base-url:http://127.0.0.1:6333}") String baseUrl,
            @Value("${app.rag.vector.api-key:}") String apiKey,
            @Value("${app.rag.vector.collection:alz_ad_knowledge}") String collection,
            @Value("${app.rag.vector.score-threshold:0.72}") double scoreThreshold,
            @Value("${app.rag.vector.bootstrap:true}") boolean bootstrap,
            @Value("${app.rag.vector.payload-index-enabled:true}") boolean payloadIndexEnabled,
            @Value("${app.rag.embedding.model:BAAI/bge-m3}") String embeddingModel
    ) {
        if (collection == null || !collection.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Qdrant collection 名称不合法");
        }
        this.fallbackRetriever = fallbackRetriever;
        this.embeddingClient = embeddingClient;
        this.restClient = restClientBuilder.clone().baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.bootstrap = bootstrap;
        this.payloadIndexEnabled = payloadIndexEnabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.collection = collection;
        this.indexVersion = calculateIndexVersion(objectMapper, fallbackRetriever.allDocuments(), embeddingModel);
        this.scoreThreshold = Math.max(0.0, Math.min(scoreThreshold, 1.0));
    }

    public boolean warmUp() {
        return !enabled || ensureReady();
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public List<KnowledgeDocument> retrieve(String question, AdQuestionCategory category, int limit) {
        if (!enabled) {
            return fallbackRetriever.retrieve(question, category, limit);
        }
        if (question == null || question.isBlank() || category == null || limit <= 0) {
            return List.of();
        }
        try {
            if (!ensureReady()) {
                return fallbackRetriever.retrieve(question, category, limit);
            }
            List<Double> queryVector = embeddingClient.embed(question).orElse(null);
            if (queryVector == null) {
                return fallbackRetriever.retrieve(question, category, limit);
            }
            return query(queryVector, category, limit);
        } catch (RuntimeException exception) {
            LOGGER.warn("Qdrant 检索失败，使用本地轻量检索降级: {}", exception.getMessage());
            return fallbackRetriever.retrieve(question, category, limit);
        }
    }

    private boolean ensureReady() {
        if (ready.get()) {
            return true;
        }
        synchronized (initializationMonitor) {
            if (ready.get()) {
                return true;
            }
            if (!bootstrap) {
                if (!collectionExists()) {
                    LOGGER.warn("Qdrant collection {} 不存在，使用本地检索降级", collection);
                    return false;
                }
                if (collectionPointCount() <= 0) {
                    LOGGER.warn("Qdrant collection {} 没有 points，使用本地检索降级", collection);
                    return false;
                }
                ready.set(true);
                return true;
            }

            List<KnowledgeDocument> documents = fallbackRetriever.allDocuments();
            if (documents.isEmpty()) {
                LOGGER.warn("本地知识库为空，无法初始化 Qdrant collection {}", collection);
                return false;
            }
            if (indexIsCurrent(documents)) {
                ready.set(true);
                LOGGER.info("Qdrant 知识索引版本已匹配，跳过重复向量生成: collection={}, documents={}, version={}",
                        collection, documents.size(), shortIndexVersion());
                return true;
            }
            LOGGER.info("Qdrant 知识索引缺失或版本已变化，开始生成 {} 条知识向量: collection={}, version={}",
                    documents.size(), collection, shortIndexVersion());
            List<String> texts = documents.stream().map(QdrantKnowledgeRetriever::embeddingText).toList();
            List<List<Double>> vectors = embeddingClient.embedAll(texts).orElse(null);
            if (vectors == null || vectors.size() != documents.size()) {
                LOGGER.warn("无法生成知识库向量，使用本地检索降级");
                return false;
            }
            int dimensions = vectors.get(0).size();
            if (dimensions == 0 || vectors.stream().anyMatch(vector -> vector.size() != dimensions)) {
                LOGGER.warn("Embedding 服务返回了不一致的向量维度");
                return false;
            }
            createCollectionIfMissing(dimensions);
            upsertDocuments(documents, vectors);
            long pointCount = collectionPointCount();
            if (pointCount != documents.size()) {
                LOGGER.warn("Qdrant points 数量未达到预期，保持未就绪状态: collection={}, expected={}, actual={}",
                        collection, documents.size(), pointCount);
                return false;
            }
            if (payloadIndexEnabled) {
                createCategoryIndex();
            } else {
                LOGGER.info("Qdrant category payload index 已禁用，将使用 payload 扫描过滤: collection={}", collection);
            }
            ready.set(true);
            LOGGER.info("Qdrant 知识索引已就绪: collection={}, documents={}, dimensions={}",
                    collection, documents.size(), dimensions);
            return true;
        }
    }

    private boolean collectionExists() {
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get()
                    .uri("/collections/{collection}", collection);
            addApiKey(request);
            request.retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound exception) {
            return false;
        }
    }

    private void createCollectionIfMissing(int dimensions) {
        if (collectionExists()) {
            return;
        }
        RestClient.RequestBodySpec request = restClient.put()
                .uri("/collections/{collection}", collection)
                .contentType(MediaType.APPLICATION_JSON);
        addApiKey(request);
        request.body(Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")))
                .retrieve()
                .toBodilessEntity();
    }

    private void createCategoryIndex() {
        try {
            RestClient.RequestBodySpec request = restClient.put()
                    .uri("/collections/{collection}/index?wait=true", collection)
                    .contentType(MediaType.APPLICATION_JSON);
            addApiKey(request);
            request.body(Map.of("field_name", "category", "field_schema", "keyword"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException exception) {
            LOGGER.warn("Qdrant category payload index 创建失败，将使用 payload 扫描过滤: collection={}, reason={}",
                    collection, exception.getMessage());
        }
    }

    private void upsertDocuments(List<KnowledgeDocument> documents, List<List<Double>> vectors) {
        for (int from = 0; from < documents.size(); from += UPSERT_BATCH_SIZE) {
            int to = Math.min(from + UPSERT_BATCH_SIZE, documents.size());
            List<Map<String, Object>> points = new ArrayList<>(to - from);
            for (int index = from; index < to; index++) {
                KnowledgeDocument document = documents.get(index);
                Map<String, Object> payload = objectMapper.convertValue(
                        document, new TypeReference<Map<String, Object>>() { }
                );
                payload.put(INDEX_VERSION_PAYLOAD_KEY, indexVersion);
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", pointId(document));
                point.put("vector", vectors.get(index));
                point.put("payload", payload);
                points.add(point);
            }
            RestClient.RequestBodySpec request = restClient.put()
                    .uri("/collections/{collection}/points?wait=true", collection)
                    .contentType(MediaType.APPLICATION_JSON);
            addApiKey(request);
            request.body(Map.of("points", points)).retrieve().toBodilessEntity();
        }
    }

    private long collectionPointCount() {
        RestClient.RequestHeadersSpec<?> request = restClient.get()
                .uri("/collections/{collection}", collection);
        addApiKey(request);
        JsonNode response = request.retrieve().body(JsonNode.class);
        JsonNode pointsCount = response == null ? null : response.path("result").path("points_count");
        return pointsCount != null && pointsCount.canConvertToLong() ? pointsCount.longValue() : -1L;
    }

    private List<KnowledgeDocument> query(
            List<Double> vector,
            AdQuestionCategory category,
            int limit
    ) {
        Map<String, Object> body = Map.of(
                "query", vector,
                "filter", Map.of("must", List.of(Map.of(
                        "key", "category",
                        "match", Map.of("value", category.name())
                ))),
                "limit", Math.min(limit, 8),
                "score_threshold", scoreThreshold,
                "with_payload", true
        );
        RestClient.RequestBodySpec request = restClient.post()
                .uri("/collections/{collection}/points/query", collection)
                .contentType(MediaType.APPLICATION_JSON);
        addApiKey(request);
        JsonNode response = request.body(body).retrieve().body(JsonNode.class);
        JsonNode points = response == null ? null : response.path("result").path("points");
        if (points == null || !points.isArray()) {
            return List.of();
        }
        List<KnowledgeDocument> documents = new ArrayList<>();
        points.forEach(point -> {
            JsonNode payload = point.path("payload");
            if (payload.isObject()) {
                ObjectNode documentPayload = ((ObjectNode) payload).deepCopy();
                documentPayload.remove(INDEX_VERSION_PAYLOAD_KEY);
                KnowledgeDocument document = objectMapper.convertValue(documentPayload, KnowledgeDocument.class);
                documents.add(document);
            }
        });
        return List.copyOf(documents);
    }

    private void addApiKey(RestClient.RequestHeadersSpec<?> request) {
        if (!apiKey.isBlank()) {
            request.header("api-key", apiKey);
        }
    }

    private boolean indexIsCurrent(List<KnowledgeDocument> documents) {
        if (!collectionExists() || collectionPointCount() != documents.size()) {
            return false;
        }
        String pointId = pointId(documents.get(0));
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get()
                    .uri("/collections/{collection}/points/{pointId}", collection, pointId);
            addApiKey(request);
            JsonNode response = request.retrieve().body(JsonNode.class);
            String storedVersion = response == null ? "" : response.path("result").path("payload")
                    .path(INDEX_VERSION_PAYLOAD_KEY).asText("");
            return indexVersion.equals(storedVersion);
        } catch (HttpClientErrorException.NotFound exception) {
            return false;
        }
    }

    String indexVersion() {
        return indexVersion;
    }

    private String shortIndexVersion() {
        return indexVersion.substring(0, Math.min(indexVersion.length(), 12));
    }

    private static String pointId(KnowledgeDocument document) {
        return UUID.nameUUIDFromBytes(document.id().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String calculateIndexVersion(ObjectMapper objectMapper,
                                                List<KnowledgeDocument> documents,
                                                String embeddingModel) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("qdrant-index-v1\n".getBytes(StandardCharsets.UTF_8));
            digest.update(String.valueOf(embeddingModel).trim().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(objectMapper.writeValueAsBytes(documents));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 Qdrant 知识索引版本", exception);
        }
    }

    private static String embeddingText(KnowledgeDocument document) {
        return "知识域：%s\n问题：%s\n答案：%s\n关键词：%s".formatted(
                document.category().displayName(),
                document.question(),
                document.answer(),
                String.join("、", document.keywords())
        );
    }
}
