package com.alz.screening.application;

import com.alz.config.ScreeningConsentPolicy;
import com.alz.config.StoragePaths;
import com.alz.entity.AudioRecord;
import com.alz.screening.api.ScreeningTaskResponse;
import com.alz.screening.domain.OutboxEvent;
import com.alz.screening.domain.ScreeningTask;
import com.alz.screening.domain.ScreeningTaskStatus;
import com.alz.screening.persistence.OutboxEventMapper;
import com.alz.screening.persistence.ScreeningTaskMapper;
import com.alz.service.AudioService;
import com.alz.service.DiagnosisReportService;
import com.alz.service.PdfReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScreeningTaskServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void createsQueuedTaskAndOutboxInOneServiceCall() throws Exception {
        Fixture fixture = fixture();
        AudioRecord audio = audio();
        Files.createDirectories(fixture.paths.audioDirectory());
        Files.write(fixture.paths.resolveAudio(audio.getFilePath()), new byte[]{1, 2, 3});
        when(fixture.audioService.findOwnedById(8L, 7L)).thenReturn(audio);
        when(fixture.taskMapper.findByIdempotencyKey(7L, "request-1")).thenReturn(null);
        when(fixture.taskMapper.findByAudioRecordId(8L)).thenReturn(null);
        when(fixture.taskMapper.countActiveByUser(7L)).thenReturn(0);
        when(fixture.diagnosisService.countByAudioName("sample.wav")).thenReturn(0L);
        AtomicReference<ScreeningTask> inserted = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return null;
        }).when(fixture.taskMapper).insert(any(ScreeningTask.class));
        when(fixture.taskMapper.findById(anyString())).thenAnswer(invocation -> inserted.get());

        ScreeningTaskResponse response = fixture.service.createForAudioId(8L, 7L, "request-1");

        assertNotNull(response.taskId());
        assertEquals("QUEUED", response.status());
        assertEquals(8L, response.audioId());
        verify(fixture.outboxMapper).insert(any(OutboxEvent.class));
    }

    @Test
    void returnsExistingTaskForRepeatedIdempotencyKey() {
        Fixture fixture = fixture();
        AudioRecord audio = audio();
        ScreeningTask existing = task("task-existing");
        when(fixture.audioService.findOwnedById(8L, 7L)).thenReturn(audio);
        when(fixture.taskMapper.findByIdempotencyKey(7L, "request-1")).thenReturn(existing);

        ScreeningTaskResponse response = fixture.service.createForAudioId(8L, 7L, "request-1");

        assertEquals("task-existing", response.taskId());
        verify(fixture.taskMapper, never()).insert(any());
        verify(fixture.outboxMapper, never()).insert(any());
    }

    @Test
    void cancelsQueuedTaskImmediatelyAndWritesAttemptMarker() throws Exception {
        Fixture fixture = fixture();
        ScreeningTask queued = task("62f7d621-295d-4d43-83f1-09ea40196a1a");
        ScreeningTask cancelled = task(queued.getId());
        cancelled.setStatus(ScreeningTaskStatus.CANCELLED);
        cancelled.setCurrentStage("CANCELLED");
        when(fixture.taskMapper.findOwned(queued.getId(), 7L)).thenReturn(queued);
        when(fixture.taskMapper.markCancelledIfNotStarted(queued.getId())).thenReturn(1);
        when(fixture.taskMapper.findById(queued.getId())).thenReturn(cancelled);

        ScreeningTaskResponse response = fixture.service.cancel(queued.getId(), 7L);

        assertEquals("CANCELLED", response.status());
        Path marker = fixture.paths.resolveScreeningArtifact(queued.getId(), "cancel.requested");
        assertEquals("0", Files.readString(marker));
        verify(fixture.taskMapper, never()).requestCancellation(queued.getId());
    }

    @Test
    void requestsCancellationForRunningTaskAndWritesAttemptMarker() throws Exception {
        Fixture fixture = fixture();
        ScreeningTask running = task("72f7d621-295d-4d43-83f1-09ea40196a1a");
        running.setStatus(ScreeningTaskStatus.TRANSCRIBING);
        running.setCurrentStage("TRANSCRIPTION");
        running.setProgress(20);
        ScreeningTask requested = task(running.getId());
        requested.setStatus(ScreeningTaskStatus.CANCEL_REQUESTED);
        requested.setCurrentStage("CANCELLATION");
        when(fixture.taskMapper.findOwned(running.getId(), 7L)).thenReturn(running);
        when(fixture.taskMapper.markCancelledIfNotStarted(running.getId())).thenReturn(0);
        when(fixture.taskMapper.requestCancellation(running.getId())).thenReturn(1);
        when(fixture.taskMapper.findById(running.getId())).thenReturn(requested);

        ScreeningTaskResponse response = fixture.service.cancel(running.getId(), 7L);

        assertEquals("CANCEL_REQUESTED", response.status());
        assertTrue(Files.isRegularFile(
                fixture.paths.resolveScreeningArtifact(running.getId(), "cancel.requested")));
        verify(fixture.taskMapper).requestCancellation(running.getId());
    }

    @Test
    void requeuesCancelledTaskWithNextAttemptAndDiscardsOldAnalysis() throws Exception {
        Fixture fixture = fixture();
        ScreeningTask cancelled = task("82f7d621-295d-4d43-83f1-09ea40196a1a");
        cancelled.setStatus(ScreeningTaskStatus.CANCELLED);
        Files.createDirectories(fixture.paths.audioDirectory());
        Files.write(fixture.paths.resolveAudio(cancelled.getAudioName()), new byte[]{1, 2, 3});
        Path analysis = fixture.paths.resolveScreeningArtifact(cancelled.getId(), "analysis.json");
        Files.createDirectories(analysis.getParent());
        Files.writeString(analysis, "{}");
        Files.writeString(fixture.paths.resolveScreeningArtifact(cancelled.getId(), "cancel.requested"), "0");

        ScreeningTask retried = task(cancelled.getId());
        retried.setStatus(ScreeningTaskStatus.QUEUED);
        retried.setAttemptCount(1);
        when(fixture.taskMapper.findOwned(cancelled.getId(), 7L)).thenReturn(cancelled);
        when(fixture.taskMapper.retryTerminal(cancelled.getId())).thenReturn(1);
        when(fixture.taskMapper.findById(cancelled.getId())).thenReturn(retried);

        ScreeningTaskResponse response = fixture.service.retry(cancelled.getId(), 7L, "retry-1");

        assertEquals("QUEUED", response.status());
        assertEquals(1, retried.getAttemptCount());
        assertTrue(Files.notExists(analysis));
        assertEquals("0", Files.readString(
                fixture.paths.resolveScreeningArtifact(cancelled.getId(), "cancel.requested")));
        verify(fixture.outboxMapper).insert(any(OutboxEvent.class));
    }

    private Fixture fixture() {
        ScreeningTaskMapper taskMapper = mock(ScreeningTaskMapper.class);
        OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
        AudioService audioService = mock(AudioService.class);
        DiagnosisReportService diagnosisService = mock(DiagnosisReportService.class);
        PdfReportService pdfService = mock(PdfReportService.class);
        StoragePaths paths = new StoragePaths(
                tempDirectory.resolve("audio").toString(),
                tempDirectory.resolve("pdf").toString(),
                tempDirectory.resolve("admin").toString(),
                tempDirectory.resolve("artifacts").toString());
        ScreeningEventFactory eventFactory = new ScreeningEventFactory(
                new ObjectMapper().findAndRegisterModules());
        ScreeningTaskService service = new ScreeningTaskService(
                taskMapper, outboxMapper, audioService, diagnosisService, pdfService,
                eventFactory, paths, true, 2, "deepseek-v4-flash");
        return new Fixture(service, taskMapper, outboxMapper, audioService,
                diagnosisService, paths);
    }

    private AudioRecord audio() {
        AudioRecord audio = new AudioRecord();
        audio.setId(8L);
        audio.setUserId(7L);
        audio.setFilePath("sample.wav");
        audio.setConsentVersion(ScreeningConsentPolicy.CURRENT_VERSION);
        audio.setTaskType(ScreeningConsentPolicy.DEFAULT_TASK_TYPE);
        return audio;
    }

    private ScreeningTask task(String id) {
        ScreeningTask task = new ScreeningTask();
        task.setId(id);
        task.setUserId(7L);
        task.setAudioRecordId(8L);
        task.setAudioName("sample.wav");
        task.setStatus(ScreeningTaskStatus.QUEUED);
        task.setCurrentStage("QUEUED");
        task.setProgress(0);
        task.setAttemptCount(0);
        task.setTraceId("5b351bbc-7894-4f34-b977-9626d1648534");
        return task;
    }

    private record Fixture(
            ScreeningTaskService service,
            ScreeningTaskMapper taskMapper,
            OutboxEventMapper outboxMapper,
            AudioService audioService,
            DiagnosisReportService diagnosisService,
            StoragePaths paths
    ) { }
}
