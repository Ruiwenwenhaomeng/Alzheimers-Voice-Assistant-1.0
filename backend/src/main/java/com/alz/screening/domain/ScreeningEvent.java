package com.alz.screening.domain;

import java.time.Instant;
import java.util.Map;

public record ScreeningEvent(
        String eventId,
        String eventType,
        int schemaVersion,
        String taskId,
        Long userId,
        Long audioId,
        String traceId,
        int attempt,
        Instant occurredAt,
        Map<String, Object> payload
) {
    public ScreeningEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
