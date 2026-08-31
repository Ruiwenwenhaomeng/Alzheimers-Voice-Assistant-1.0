package com.alz.screening.outbox;

import com.alz.screening.domain.OutboxEvent;
import com.alz.screening.domain.ScreeningEvent;
import com.alz.screening.messaging.ScreeningMessagingConfig;
import com.alz.screening.persistence.OutboxEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "app.screening.async", name = "enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final long confirmTimeoutMs;

    public OutboxPublisher(
            OutboxEventMapper outboxMapper,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${app.screening.async.outbox-batch-size:20}") int batchSize,
            @Value("${app.screening.async.publisher-confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.confirmTimeoutMs = Math.max(1000, confirmTimeoutMs);
        this.rabbitTemplate.setMandatory(true);
    }

    @Scheduled(fixedDelayString = "${app.screening.async.outbox-delay-ms:1000}")
    public void publishReadyEvents() {
        for (OutboxEvent outbox : outboxMapper.findReady(batchSize)) {
            publish(outbox);
        }
    }

    private void publish(OutboxEvent outbox) {
        try {
            ScreeningEvent event = objectMapper.readValue(outbox.getPayload(), ScreeningEvent.class);
            CorrelationData correlation = new CorrelationData(outbox.getEventId());
            rabbitTemplate.convertAndSend(
                    ScreeningMessagingConfig.EXCHANGE,
                    outbox.getEventType(),
                    event,
                    message -> {
                        message.getMessageProperties().setCorrelationId(event.taskId());
                        message.getMessageProperties().setHeader("eventId", event.eventId());
                        message.getMessageProperties().setHeader("traceId", event.traceId());
                        return message;
                    },
                    correlation
            );
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ publisher confirm failed: " + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ returned unroutable event: "
                        + correlation.getReturned().getReplyText());
            }
            outboxMapper.markPublished(outbox.getEventId());
        } catch (JsonProcessingException exception) {
            LOGGER.error("Outbox 事件无法反序列化: eventId={}", outbox.getEventId());
            outboxMapper.markFailed(outbox.getEventId(), LocalDateTime.now().plusMinutes(5));
        } catch (Exception exception) {
            int attempts = outbox.getAttemptCount() == null ? 0 : outbox.getAttemptCount();
            long delaySeconds = Math.min(300, Math.max(5, 5L << Math.min(attempts, 6)));
            outboxMapper.markFailed(outbox.getEventId(), LocalDateTime.now().plusSeconds(delaySeconds));
            LOGGER.warn("Outbox 发布失败，将稍后重试: eventId={}, reason={}",
                    outbox.getEventId(), exception.getClass().getSimpleName());
        }
    }
}
