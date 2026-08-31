package com.alz.screening.application;

import com.alz.screening.domain.OutboxEvent;
import com.alz.screening.domain.ScreeningEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class ScreeningEventFactory {

    private final ObjectMapper objectMapper;

    public ScreeningEventFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ScreeningEvent event(String eventId, String eventType, String taskId,
                                Long userId, Long audioId, String traceId,
                                int attempt, Map<String, Object> payload) {
        return new ScreeningEvent(eventId, eventType, 1, taskId, userId, audioId,
                traceId, attempt, Instant.now(), payload);
    }

    public String deterministicEventId(String taskId, String stage, int attempt) {
        String value = taskId + ":" + stage + ":v1:" + attempt;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public OutboxEvent outbox(ScreeningEvent event) {
        OutboxEvent outbox = new OutboxEvent();
        outbox.setEventId(event.eventId());
        outbox.setAggregateType("SCREENING_TASK");
        outbox.setAggregateId(event.taskId());
        outbox.setEventType(event.eventType());
        outbox.setSchemaVersion(event.schemaVersion());
        try {
            outbox.setPayload(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化筛查事件", exception);
        }
        return outbox;
    }
}
