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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScreeningResultListenerTest {

    @Test
    void rejectsQualityOnlyResultWhenAiIsRequired() {
        ScreeningTaskMapper taskMapper = mock(ScreeningTaskMapper.class);
        ConsumedEventMapper consumed = mock(ConsumedEventMapper.class);
        ScreeningArtifactMapper artifacts = mock(ScreeningArtifactMapper.class);
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        DiagnosisReportService reports = mock(DiagnosisReportService.class);
        ScreeningArtifactReader reader = mock(ScreeningArtifactReader.class);
        ScreeningEventFactory events = mock(ScreeningEventFactory.class);
        ScreeningResultListener listener = new ScreeningResultListener(
                taskMapper, consumed, artifacts, outbox, reports, reader, events, true);

        String taskId = "62f7d621-295d-4d43-83f1-09ea40196a1a";
        ScreeningEvent event = new ScreeningEvent(
                "0c640965-f423-4196-aae2-6392ad99de55",
                "screening.analysis.completed.v1", 1, taskId, 7L, 8L,
                "5b351bbc-7894-4f34-b977-9626d1648534", 0, Instant.now(),
                Map.of("artifactUri", taskId + "/analysis.json", "sha256", "a".repeat(64)));
        ScreeningTask task = new ScreeningTask();
        task.setId(taskId);
        task.setUserId(7L);
        task.setAudioRecordId(8L);
        task.setStatus(ScreeningTaskStatus.RESULT_PERSISTING);
        task.setAttemptCount(0);
        AudioDiagnosis diagnosis = new AudioDiagnosis();
        diagnosis.setModelVersion("quality-only-v1");

        when(consumed.claim("java-result-v1", event.eventId())).thenReturn(1);
        when(taskMapper.findById(taskId)).thenReturn(task);
        when(reader.readAnalysis(taskId, taskId + "/analysis.json", "a".repeat(64)))
                .thenReturn(diagnosis);

        listener.persistResult(event);

        verify(taskMapper).markFailed(taskId, event.attempt(), "AI_CONFIGURATION", "AI_ENGINE_NOT_CONFIGURED",
                "AI 筛查已启用，但 worker 返回了仅录音质量检查结果；请重启 worker 后重试");
        verifyNoInteractions(artifacts, outbox, reports);
    }
}
