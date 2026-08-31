package com.alz.assistant.memory;

import com.alz.assistant.ConversationContext;
import com.alz.assistant.ConversationTurn;
import com.alz.assistant.DeepSeekClient;
import com.alz.assistant.ThinkContentFilter;
import com.alz.dto.AssistantChatResponse;
import com.alz.dto.AssistantConversationDetail;
import com.alz.dto.AssistantConversationSummary;
import com.alz.dto.AssistantMessageView;
import com.alz.dto.AssistantSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AssistantConversationService {

    private static final String CACHE_PREFIX = "ASSISTANT:MEMORY:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final AssistantConversationMapper conversationMapper;
    private final AssistantMessageMapper messageMapper;
    private final AssistantMemoryMapper memoryMapper;
    private final DeepSeekClient deepSeekClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AssistantMemoryProperties properties;

    public AssistantConversationService(AssistantConversationMapper conversationMapper,
                                        AssistantMessageMapper messageMapper,
                                        AssistantMemoryMapper memoryMapper,
                                        DeepSeekClient deepSeekClient,
                                        StringRedisTemplate redisTemplate,
                                        ObjectMapper objectMapper,
                                        AssistantMemoryProperties properties) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.memoryMapper = memoryMapper;
        this.deepSeekClient = deepSeekClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public AssistantConversationSummary create(ConversationOwner owner, String requestedTitle) {
        AssistantConversation conversation = new AssistantConversation();
        conversation.setId(UUID.randomUUID().toString());
        conversation.setOwnerType(owner.type());
        conversation.setOwnerKey(owner.key());
        conversation.setTitle(cleanTitle(requestedTitle, "新对话"));
        conversationMapper.insert(conversation);
        return summary(conversationMapper.findOwned(conversation.getId(), owner.type(), owner.key()));
    }

    public List<AssistantConversationSummary> list(ConversationOwner owner) {
        return conversationMapper.listOwned(owner.type(), owner.key()).stream().map(this::summary).toList();
    }

    public AssistantConversationDetail detail(ConversationOwner owner, String conversationId) {
        AssistantConversation conversation = requireOwned(owner, conversationId);
        List<AssistantMessageView> messages = messageMapper.listAll(conversationId).stream()
                .map(this::view).toList();
        return new AssistantConversationDetail(summary(conversation), messages);
    }

    @Transactional
    public void delete(ConversationOwner owner, String conversationId) {
        if (conversationMapper.deleteOwned(conversationId, owner.type(), owner.key()) == 0) {
            throw new IllegalArgumentException("对话不存在或无权访问");
        }
        deleteCache(conversationId);
    }

    @Transactional
    public TurnPreparation prepareTurn(ConversationOwner owner, String conversationId, String question) {
        validateQuestion(question);
        AssistantConversation existing = requireOwned(owner, conversationId);
        if (existing.getUserTurnCount() >= properties.getMaxTurns()) {
            throw new IllegalStateException("该对话已达到 100 轮上限，请新建对话");
        }
        int updated = conversationMapper.acquireTurn(conversationId, owner.type(), owner.key(),
                properties.getMaxTurns(), properties.getGenerationTimeoutSeconds());
        if (updated == 0) {
            AssistantConversation current = requireOwned(owner, conversationId);
            if (current.getUserTurnCount() >= properties.getMaxTurns()) {
                throw new IllegalStateException("该对话已达到 100 轮上限，请新建对话");
            }
            throw new IllegalStateException("当前对话正在生成回答，请等待完成后再发送");
        }
        AssistantConversation current = requireOwned(owner, conversationId);
        ConversationContext context = readCachedContext(conversationId);
        if (context == null) {
            context = buildContext(current, current.getUserTurnCount());
        }
        AssistantMessage user = new AssistantMessage();
        user.setConversationId(conversationId);
        user.setTurnNo(current.getUserTurnCount());
        user.setRole("USER");
        user.setContent(question.trim());
        messageMapper.insert(user);
        return new TurnPreparation(conversationId, current.getUserTurnCount(),
                properties.getMaxTurns(), question.trim(), context);
    }

    @Transactional
    public AssistantConversationSummary completeTurn(ConversationOwner owner, TurnPreparation turn,
                                                      AssistantChatResponse response) {
        AssistantConversation conversation = requireOwned(owner, turn.conversationId());
        AssistantMessage assistant = new AssistantMessage();
        assistant.setConversationId(turn.conversationId());
        assistant.setTurnNo(turn.turnNo());
        assistant.setRole("ASSISTANT");
        assistant.setContent(ThinkContentFilter.strip(response.answer()));
        assistant.setTitle(response.title());
        assistant.setIntent(response.intent());
        assistant.setUrgent(response.urgent());
        assistant.setMetadataJson(writeMetadata(response));
        messageMapper.insert(assistant);

        String title = turn.turnNo() == 1 && "新对话".equals(conversation.getTitle())
                ? cleanTitle(turn.question(), "新对话") : conversation.getTitle();
        conversationMapper.completeTurn(turn.conversationId(), title);
        deleteCache(turn.conversationId());
        AssistantConversation completed = requireOwned(owner, turn.conversationId());
        return summary(completed);
    }

    /** Runs after the short message transaction, so the model summary call never holds a DB transaction open. */
    public AssistantConversationSummary refreshMemoryAfterTurn(ConversationOwner owner,
                                                               String conversationId,
                                                               int completedTurn) {
        AssistantConversation conversation = requireOwned(owner, conversationId);
        summarizeIfNeeded(conversation);
        AssistantConversation refreshed = requireOwned(owner, conversationId);
        cacheContext(refreshed, refreshed.getUserTurnCount() + 1);
        return summary(refreshed);
    }

    @Transactional
    public void failTurn(ConversationOwner owner, TurnPreparation turn, String message) {
        requireOwned(owner, turn.conversationId());
        AssistantMessage assistant = new AssistantMessage();
        assistant.setConversationId(turn.conversationId());
        assistant.setTurnNo(turn.turnNo());
        assistant.setRole("ASSISTANT");
        assistant.setContent(message == null || message.isBlank() ? "回答生成失败，请稍后重试。" : message);
        assistant.setTitle("回答暂时中断");
        assistant.setIntent("generation_error");
        assistant.setMetadataJson("{}");
        messageMapper.insert(assistant);
        conversationMapper.release(turn.conversationId());
        deleteCache(turn.conversationId());
    }

    private void summarizeIfNeeded(AssistantConversation conversation) {
        int interval = properties.getSummaryInterval();
        int from = conversation.getSummaryUpToTurn() + 1;
        int to = from + interval - 1;
        if (to > conversation.getUserTurnCount()) {
            return;
        }
        List<ConversationTurn> turns = toTurns(messageMapper.listRange(conversation.getId(), from, to));
        String segmentSummary = compactTurns(turns, Math.max(600, properties.getRollingSummaryMaxChars() / 2));
        memoryMapper.insert(conversation.getId(), from, to, segmentSummary);

        String rolling = deepSeekClient.summarize(conversation.getRollingSummary(), turns)
                .map(ThinkContentFilter::strip)
                .filter(value -> !value.isBlank())
                .orElseGet(() -> mergeSummary(conversation.getRollingSummary(), segmentSummary));
        conversationMapper.updateSummary(conversation.getId(), cap(rolling), to);
    }

    private ConversationContext buildContext(AssistantConversation conversation, int beforeTurn) {
        List<AssistantMessage> messages = messageMapper.listAfterTurn(
                conversation.getId(), conversation.getSummaryUpToTurn());
        List<AssistantMessage> prior = messages.stream()
                .filter(message -> message.getTurnNo() < beforeTurn)
                .toList();
        return new ConversationContext(conversation.getRollingSummary(), toTurns(prior));
    }

    private List<ConversationTurn> toTurns(List<AssistantMessage> messages) {
        Map<Integer, String[]> grouped = new LinkedHashMap<>();
        for (AssistantMessage message : messages) {
            String[] pair = grouped.computeIfAbsent(message.getTurnNo(), ignored -> new String[2]);
            if ("USER".equals(message.getRole())) pair[0] = message.getContent();
            if ("ASSISTANT".equals(message.getRole())) pair[1] = message.getContent();
        }
        return grouped.entrySet().stream()
                .filter(entry -> entry.getValue()[0] != null && entry.getValue()[1] != null)
                .map(entry -> new ConversationTurn(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .toList();
    }

    private void cacheContext(AssistantConversation conversation, int beforeTurn) {
        try {
            ConversationContext context = buildContext(conversation, beforeTurn);
            redisTemplate.opsForValue().set(CACHE_PREFIX + conversation.getId(),
                    objectMapper.writeValueAsString(context), properties.getCacheTtlMinutes(), TimeUnit.MINUTES);
        } catch (RuntimeException | JsonProcessingException ignored) {
            // Redis is an optimization; MySQL remains the source of truth.
        }
    }

    private ConversationContext readCachedContext(String conversationId) {
        try {
            String json = redisTemplate.opsForValue().get(CACHE_PREFIX + conversationId);
            return json == null ? null : objectMapper.readValue(json, ConversationContext.class);
        } catch (RuntimeException | JsonProcessingException ignored) {
            return null;
        }
    }

    private void deleteCache(String conversationId) {
        try { redisTemplate.delete(CACHE_PREFIX + conversationId); } catch (RuntimeException ignored) { }
    }

    private String writeMetadata(AssistantChatResponse response) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "actionSuggestions", response.actionSuggestions(),
                    "medicalDisclaimer", response.medicalDisclaimer(),
                    "sources", response.sources()
            ));
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private AssistantMessageView view(AssistantMessage message) {
        List<String> suggestions = List.of();
        String disclaimer = "";
        List<AssistantSource> sources = List.of();
        if (message.getMetadataJson() != null && !message.getMetadataJson().isBlank()) {
            try {
                Map<String, Object> metadata = objectMapper.readValue(message.getMetadataJson(), MAP_TYPE);
                suggestions = objectMapper.convertValue(metadata.getOrDefault("actionSuggestions", List.of()),
                        new TypeReference<>() { });
                disclaimer = String.valueOf(metadata.getOrDefault("medicalDisclaimer", ""));
                sources = objectMapper.convertValue(metadata.getOrDefault("sources", List.of()),
                        new TypeReference<>() { });
            } catch (RuntimeException | JsonProcessingException ignored) { }
        }
        return new AssistantMessageView(message.getId() == null ? 0 : message.getId(), message.getTurnNo(),
                message.getRole().toLowerCase(), message.getContent(), message.getTitle(), message.getIntent(),
                message.isUrgent(), suggestions, disclaimer, sources, message.getCreatedAt());
    }

    private AssistantConversation requireOwned(ConversationOwner owner, String id) {
        AssistantConversation conversation = conversationMapper.findOwned(id, owner.type(), owner.key());
        if (conversation == null) throw new IllegalArgumentException("对话不存在或无权访问");
        return conversation;
    }

    private AssistantConversationSummary summary(AssistantConversation value) {
        return new AssistantConversationSummary(value.getId(), value.getTitle(), value.getUserTurnCount(),
                properties.getMaxTurns(), value.getSummaryUpToTurn(),
                "GENERATING".equals(value.getGenerationStatus()), value.getCreatedAt(), value.getUpdatedAt());
    }

    private String compactTurns(List<ConversationTurn> turns, int limit) {
        StringBuilder result = new StringBuilder();
        for (ConversationTurn turn : turns) {
            result.append("第").append(turn.turnNo()).append("轮：用户")
                    .append(shorten(turn.userMessage(), 140)).append("；助手")
                    .append(shorten(turn.assistantMessage(), 220)).append('\n');
        }
        return result.length() <= limit ? result.toString().trim()
                : result.substring(result.length() - limit).trim();
    }

    private String mergeSummary(String oldSummary, String segment) {
        return (oldSummary == null || oldSummary.isBlank()) ? segment : oldSummary + "\n" + segment;
    }

    private String cap(String value) {
        int max = properties.getRollingSummaryMaxChars();
        if (value.length() <= max) return value;
        String prefix = "（较早记忆已压缩）";
        return prefix + value.substring(value.length() - (max - prefix.length()));
    }

    private static String shorten(String value, int max) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }

    private static String cleanTitle(String title, String fallback) {
        if (title == null || title.isBlank()) return fallback;
        return shorten(title, 36);
    }

    private static void validateQuestion(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("问题不能为空");
        if (value.length() > 500) throw new IllegalArgumentException("问题不能超过500字");
    }
}
