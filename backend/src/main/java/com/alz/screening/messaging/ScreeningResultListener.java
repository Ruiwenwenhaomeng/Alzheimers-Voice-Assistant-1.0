package com.alz.screening.messaging;

import com.alz.entity.AudioDiagnosis;
import com.alz.screening.application.ScreeningArtifactReader;
import com.alz.screening.application.ScreeningEventFactory;
import com.alz.screening.domain.ScreeningEvent;
import com.alz.screening.domain.ScreeningTask;
import com.alz.screening.domain.ScreeningTaskStatus;
import com.alz.screening.persistence.ConsumedEventMapper;
import com.alz.screening.persistence.OutboxEventMapper;
import com.alz.screening.persistence.ScreeningArtifactMapper;
import com.alz.screening.persistence.ScreeningTaskMapper;
import com.alz.service.DiagnosisReportService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.screening.async", name = "enabled", havingValue = "true")
public class ScreeningResultListener {

    private static final String CONSUMER = "java-result-v1";

    private final ScreeningTaskMapper taskMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final ScreeningArtifactMapper artifactMapper;
    private final OutboxEventMapper outboxMapper;
    private final DiagnosisReportService diagnosisReportService;
    private final ScreeningArtifactReader artifactReader;
    private final ScreeningEventFactory eventFactory;
    private final boolean requireAi;

    public ScreeningResultListener(ScreeningTaskMapper taskMapper,
                                   ConsumedEventMapper consumedEventMapper,
                                   ScreeningArtifactMapper artifactMapper,
                                   OutboxEventMapper outboxMapper,
                                   DiagnosisReportService diagnosisReportService,
                                   ScreeningArtifactReader artifactReader,
                                   ScreeningEventFactory eventFactory,
                                   @Value("${app.screening.require-ai:false}") boolean requireAi) {
        this.taskMapper = taskMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.artifactMapper = artifactMapper;
        this.outboxMapper = outboxMapper;
        this.diagnosisReportService = diagnosisReportService;
        this.artifactReader = artifactReader;
        this.eventFactory = eventFactory;
        this.requireAi = requireAi;
    }

    @Transactional
    @RabbitListener(queues = ScreeningMessagingConfig.RESULT_QUEUE,
            containerFactory = "screeningResultContainerFactory")
    public void persistResult(ScreeningEvent event) {
        validate(event);
        if (consumedEventMapper.claim(CONSUMER, event.eventId()) == 0) {
            return;
        }
        ScreeningTask task = taskMapper.findById(event.taskId());
        if (task == null || !task.getUserId().equals(event.userId())
                || !task.getAudioRecordId().equals(event.audioId())) {
            throw new IllegalArgumentException("筛查结果事件与任务不匹配");
        }
        if (task.getAttemptCount() == null || task.getAttemptCount() != event.attempt()) {
            return;
        }
        if (task.getStatus() == ScreeningTaskStatus.CANCEL_REQUESTED) {
            taskMapper.markCancelled(task.getId(), event.attempt());
            return;
        }
        if (task.getStatus().isTerminal()) {
            return;
        }

        String artifactUri = required(event.payload(), "artifactUri");
        String sha256 = required(event.payload(), "sha256");
        AudioDiagnosis diagnosis = artifactReader.readAnalysis(task.getId(), artifactUri, sha256);
        if (requireAi && "quality-only-v1".equalsIgnoreCase(diagnosis.getModelVersion())) {
            taskMapper.markFailed(task.getId(), event.attempt(), "AI_CONFIGURATION", "AI_ENGINE_NOT_CONFIGURED",
                    "AI 筛查已启用，但 worker 返回了仅录音质量检查结果；请重启 worker 后重试");
            return;
        }
        artifactMapper.upsert(task.getId(), "ANALYSIS", artifactUri, sha256, 1);
        diagnosisReportService.saveReportForTask(
                task.getUserId(), task.getAudioName(), task.getId(), diagnosis);
        taskMapper.markPdfQueued(task.getId(), event.attempt(), diagnosis.getModelVersion());

        ScreeningEvent pdfRequested = eventFactory.event(
                eventFactory.deterministicEventId(task.getId(), "pdf-requested", event.attempt()),
                "pdf.requested.v1", task.getId(), task.getUserId(), task.getAudioRecordId(),
                task.getTraceId(), event.attempt(), Map.of()
        );
        outboxMapper.insert(eventFactory.outbox(pdfRequested));
    }

    private void validate(ScreeningEvent event) {
        if (event == null || !"screening.analysis.completed.v1".equals(event.eventType())
                || event.schemaVersion() != 1 || event.eventId() == null || event.taskId() == null
                || event.userId() == null || event.audioId() == null) {
            throw new IllegalArgumentException("筛查结果事件不合法");
        }
    }

    private String required(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("筛查结果事件缺少字段: " + key);
        }
        return value.toString();
    }
}
