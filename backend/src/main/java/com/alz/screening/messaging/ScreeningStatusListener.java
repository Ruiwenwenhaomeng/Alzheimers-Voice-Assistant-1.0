package com.alz.screening.messaging;

import com.alz.screening.domain.ScreeningEvent;
import com.alz.screening.persistence.ConsumedEventMapper;
import com.alz.screening.persistence.ScreeningTaskMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.screening.async", name = "enabled", havingValue = "true")
public class ScreeningStatusListener {

    private static final String CONSUMER = "java-status-v1";

    private final ScreeningTaskMapper taskMapper;
    private final ConsumedEventMapper consumedEventMapper;

    public ScreeningStatusListener(ScreeningTaskMapper taskMapper,
                                   ConsumedEventMapper consumedEventMapper) {
        this.taskMapper = taskMapper;
        this.consumedEventMapper = consumedEventMapper;
    }

    @Transactional
    @RabbitListener(queues = ScreeningMessagingConfig.STATUS_QUEUE,
            containerFactory = "screeningStatusContainerFactory")
    public void project(ScreeningEvent event) {
        validate(event);
        if (consumedEventMapper.claim(CONSUMER, event.eventId()) == 0) {
            return;
        }
        switch (event.eventType()) {
            case "screening.transcription.started.v1" ->
                    taskMapper.advance(event.taskId(), event.attempt(), "TRANSCRIBING", "TRANSCRIPTION", 20);
            case "screening.features.started.v1" ->
                    taskMapper.advance(event.taskId(), event.attempt(), "FEATURE_EXTRACTING", "FEATURES", 50);
            case "screening.llm.started.v1" ->
                    taskMapper.advance(event.taskId(), event.attempt(), "LLM_ANALYZING", "LLM", 70);
            case "screening.analysis.completed.v1" ->
                    taskMapper.advance(event.taskId(), event.attempt(), "RESULT_PERSISTING", "RESULT", 80);
            case "screening.cancelled.v1" -> taskMapper.markCancelled(event.taskId(), event.attempt());
            case "screening.stage.retrying.v1" ->
                    taskMapper.advance(event.taskId(), event.attempt(), "RETRY_WAIT", string(event.payload(), "stage", "PROCESSING"),
                            integer(event.payload(), "progress", 20));
            case "screening.stage.failed.v1", "pdf.failed.v1" ->
                    taskMapper.markFailed(event.taskId(), event.attempt(), string(event.payload(), "stage", "PROCESSING"),
                            string(event.payload(), "errorCode", "SCREENING_FAILED"),
                            string(event.payload(), "message", "后台处理失败，请稍后重试"));
            case "pdf.started.v1" ->
                    taskMapper.advance(event.taskId(), event.attempt(), "PDF_GENERATING", "PDF", 90);
            case "pdf.completed.v1" -> taskMapper.markCompleted(event.taskId(), event.attempt());
            default -> {
                // requested/completion events may be consumed by other projections.
            }
        }
    }

    private void validate(ScreeningEvent event) {
        if (event == null || event.eventId() == null || event.taskId() == null
                || event.eventType() == null || event.schemaVersion() != 1) {
            throw new IllegalArgumentException("筛查事件信封不合法");
        }
    }

    private String string(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private int integer(Map<String, Object> payload, String key, int fallback) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return Math.max(0, Math.min(100, number.intValue()));
        }
        return fallback;
    }
}
