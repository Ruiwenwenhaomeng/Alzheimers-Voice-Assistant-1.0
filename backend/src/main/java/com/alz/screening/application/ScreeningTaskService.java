package com.alz.screening.application;

import com.alz.config.ScreeningConsentPolicy;
import com.alz.config.StoragePaths;
import com.alz.entity.AudioRecord;
import com.alz.entity.DiagnosisReport;
import com.alz.entity.PdfReport;
import com.alz.service.AudioService;
import com.alz.service.DiagnosisReportService;
import com.alz.service.PdfReportService;
import com.alz.screening.api.ScreeningTaskResponse;
import com.alz.screening.domain.ScreeningEvent;
import com.alz.screening.domain.ScreeningTask;
import com.alz.screening.domain.ScreeningTaskStatus;
import com.alz.screening.persistence.OutboxEventMapper;
import com.alz.screening.persistence.ScreeningTaskMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ScreeningTaskService {

    private final ScreeningTaskMapper taskMapper;
    private final OutboxEventMapper outboxMapper;
    private final AudioService audioService;
    private final DiagnosisReportService diagnosisReportService;
    private final PdfReportService pdfReportService;
    private final ScreeningEventFactory eventFactory;
    private final StoragePaths storagePaths;
    private final boolean asyncEnabled;
    private final int maxActivePerUser;
    private final String modelVersion;

    public ScreeningTaskService(
            ScreeningTaskMapper taskMapper,
            OutboxEventMapper outboxMapper,
            AudioService audioService,
            DiagnosisReportService diagnosisReportService,
            PdfReportService pdfReportService,
            ScreeningEventFactory eventFactory,
            StoragePaths storagePaths,
            @Value("${app.screening.async.enabled:false}") boolean asyncEnabled,
            @Value("${app.screening.async.max-active-per-user:2}") int maxActivePerUser,
            @Value("${app.screening.model-version:deepseek-v4-flash}") String modelVersion) {
        this.taskMapper = taskMapper;
        this.outboxMapper = outboxMapper;
        this.audioService = audioService;
        this.diagnosisReportService = diagnosisReportService;
        this.pdfReportService = pdfReportService;
        this.eventFactory = eventFactory;
        this.storagePaths = storagePaths;
        this.asyncEnabled = asyncEnabled;
        this.maxActivePerUser = Math.max(1, maxActivePerUser);
        this.modelVersion = modelVersion;
    }

    @Transactional
    public ScreeningTaskResponse createForAudioId(Long audioId, Long userId, String idempotencyKey) {
        AudioRecord audio = audioService.findOwnedById(audioId, userId);
        if (audio == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "音频不存在");
        }
        return create(audio, userId, idempotencyKey);
    }

    @Transactional
    public ScreeningTaskResponse createForAudioName(String audioName, Long userId, String idempotencyKey) {
        AudioRecord audio = audioService.findOwnedByFilePath(audioName, userId);
        if (audio == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "音频不存在");
        }
        return create(audio, userId, idempotencyKey);
    }

    private ScreeningTaskResponse create(AudioRecord audio, Long userId, String requestedKey) {
        requireEnabled();
        if (!ScreeningConsentPolicy.CURRENT_VERSION.equals(audio.getConsentVersion())
                || !ScreeningConsentPolicy.DEFAULT_TASK_TYPE.equals(audio.getTaskType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该录音缺少当前版本的筛查知情同意");
        }
        String key = normalizeIdempotencyKey(requestedKey, audio.getId());
        ScreeningTask sameRequest = taskMapper.findByIdempotencyKey(userId, key);
        if (sameRequest != null) {
            return toResponse(sameRequest);
        }
        ScreeningTask sameAudio = taskMapper.findByAudioRecordId(audio.getId());
        if (sameAudio != null) {
            return toResponse(sameAudio);
        }
        if (!Files.isRegularFile(storagePaths.resolveAudio(audio.getFilePath()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "音频文件不存在，无法创建筛查任务");
        }
        Long existingReport = diagnosisReportService.countByAudioName(audio.getFilePath());
        if (existingReport != null && existingReport > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该音频已经存在筛查报告");
        }
        if (taskMapper.countActiveByUser(userId) >= maxActivePerUser) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "当前活动筛查任务已达到上限，请等待已有任务完成");
        }

        ScreeningTask task = new ScreeningTask();
        task.setId(UUID.randomUUID().toString());
        task.setUserId(userId);
        task.setAudioRecordId(audio.getId());
        task.setAudioName(audio.getFilePath());
        task.setIdempotencyKey(key);
        task.setStatus(ScreeningTaskStatus.QUEUED);
        task.setCurrentStage("QUEUED");
        task.setProgress(0);
        task.setModelVersion(modelVersion);
        task.setAttemptCount(0);
        task.setTraceId(UUID.randomUUID().toString());

        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException exception) {
            ScreeningTask existing = taskMapper.findByIdempotencyKey(userId, key);
            if (existing == null) {
                existing = taskMapper.findByAudioRecordId(audio.getId());
            }
            if (existing != null) {
                return toResponse(existing);
            }
            throw exception;
        }

        ScreeningEvent requested = eventFactory.event(
                eventFactory.deterministicEventId(task.getId(), "requested", 0),
                "screening.requested.v1", task.getId(), userId, audio.getId(), task.getTraceId(), 0,
                Map.of("audioName", audio.getFilePath(), "modelVersion", modelVersion)
        );
        outboxMapper.insert(eventFactory.outbox(requested));
        return toResponse(taskMapper.findById(task.getId()));
    }

    public ScreeningTaskResponse getOwned(String taskId, Long userId) {
        return toResponse(requireOwned(taskId, userId));
    }

    public List<ScreeningTaskResponse> listOwned(Long userId, boolean activeOnly, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        return taskMapper.listOwned(userId, activeOnly, safePage * safeSize, safeSize).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ScreeningTaskResponse cancel(String taskId, Long userId) {
        ScreeningTask task = requireOwned(taskId, userId);
        if (task.getStatus() == ScreeningTaskStatus.CANCELLED) {
            return toResponse(task);
        }
        if (task.getStatus() == ScreeningTaskStatus.CANCEL_REQUESTED) {
            writeCancellationMarker(task);
            return toResponse(task);
        }
        if (task.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已结束任务不能取消");
        }
        writeCancellationMarker(task);
        if (taskMapper.markCancelledIfNotStarted(taskId) == 0) {
            taskMapper.requestCancellation(taskId);
        }
        return toResponse(taskMapper.findById(taskId));
    }

    @Transactional
    public ScreeningTaskResponse retry(String taskId, Long userId, String idempotencyKey) {
        requireEnabled();
        ScreeningTask task = requireOwned(taskId, userId);
        if (task.getStatus() != ScreeningTaskStatus.FAILED
                && task.getStatus() != ScreeningTaskStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有失败或已取消任务可以重新筛查");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重试必须提供有效的 Idempotency-Key");
        }
        if (!Files.isRegularFile(storagePaths.resolveAudio(task.getAudioName()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "音频文件不存在，无法重新筛查");
        }
        if (taskMapper.retryTerminal(taskId) == 0) {
            return toResponse(taskMapper.findById(taskId));
        }
        discardPreviousAnalysis(taskId);
        ScreeningTask retried = taskMapper.findById(taskId);
        int attempt = retried.getAttemptCount() == null ? 1 : retried.getAttemptCount();
        String retriedModelVersion = retried.getModelVersion();
        if (retriedModelVersion == null || retriedModelVersion.isBlank()) {
            retriedModelVersion = modelVersion;
        }
        ScreeningEvent requested = eventFactory.event(
                eventFactory.deterministicEventId(taskId, "requested", attempt),
                "screening.requested.v1", taskId, task.getUserId(), task.getAudioRecordId(),
                task.getTraceId(), attempt,
                Map.of("audioName", task.getAudioName(), "modelVersion", retriedModelVersion)
        );
        outboxMapper.insert(eventFactory.outbox(requested));
        return toResponse(retried);
    }

    private void writeCancellationMarker(ScreeningTask task) {
        Path marker = storagePaths.resolveScreeningArtifact(task.getId(), "cancel.requested");
        int attempt = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, Integer.toString(attempt),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception exception) {
            throw new IllegalStateException("无法写入筛查取消标记", exception);
        }
    }

    private void discardPreviousAnalysis(String taskId) {
        try {
            Files.deleteIfExists(storagePaths.resolveScreeningArtifact(taskId, "analysis.json"));
            Files.deleteIfExists(storagePaths.resolveScreeningArtifact(taskId, "analysis.json.tmp"));
        } catch (Exception exception) {
            throw new IllegalStateException("无法清理上次筛查的临时结果", exception);
        }
    }

    public boolean hasActiveForAudio(String audioName, Long userId) {
        return taskMapper.countActiveByAudioName(audioName, userId) > 0;
    }

    private ScreeningTask requireOwned(String taskId, Long userId) {
        if (taskId == null || !taskId.matches("[0-9a-fA-F-]{36}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "筛查任务不存在");
        }
        ScreeningTask task = taskMapper.findOwned(taskId, userId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "筛查任务不存在");
        }
        return task;
    }

    private void requireEnabled() {
        if (!asyncEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "异步筛查尚未启用，请先配置 RabbitMQ 和 SCREENING_ASYNC_ENABLED=true");
        }
    }

    private String normalizeIdempotencyKey(String value, Long audioId) {
        String key = value == null || value.isBlank() ? "audio-" + audioId : value.trim();
        if (key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key 不合法");
        }
        return key;
    }

    private ScreeningTaskResponse toResponse(ScreeningTask task) {
        DiagnosisReport result = task.getStatus() == ScreeningTaskStatus.COMPLETED
                ? diagnosisReportService.findByScreeningTaskId(task.getId()) : null;
        PdfReport pdf = task.getStatus() == ScreeningTaskStatus.COMPLETED
                ? pdfReportService.findByScreeningTaskId(task.getId()) : null;
        Map<String, String> links = new LinkedHashMap<>();
        links.put("self", "/api/v1/screenings/" + task.getId());
        if (pdf != null) {
            links.put("pdf", "/audio/pdf/" + pdf.getPdfName());
            links.put("report", "/audio/pdf/view/" + pdf.getPdfName());
        }
        return new ScreeningTaskResponse(
                task.getId(), task.getAudioRecordId(), task.getAudioName(), task.getStatus().name(),
                task.getCurrentStage(), task.getProgress() == null ? 0 : task.getProgress(),
                messageFor(task.getStatus()), task.getErrorCode(), task.getErrorMessage(),
                task.getRequestedAt(), task.getUpdatedAt(), result, Map.copyOf(links)
        );
    }

    private String messageFor(ScreeningTaskStatus status) {
        return switch (status) {
            case QUEUED -> "筛查任务已受理，可以退出页面，后台将继续处理。";
            case TRANSCRIBING -> "正在识别语音。";
            case FEATURE_EXTRACTING -> "正在提取语音和语言特征。";
            case LLM_ANALYZING -> "正在生成筛查分析。";
            case RESULT_PERSISTING -> "正在保存筛查结果。";
            case PDF_QUEUED -> "筛查结果已保存，等待生成 PDF。";
            case PDF_GENERATING -> "正在生成 PDF 报告。";
            case COMPLETED -> "筛查和 PDF 报告已完成。";
            case RETRY_WAIT -> "临时失败，系统正在等待重试。";
            case FAILED -> "任务处理失败，可稍后重试。";
            case CANCEL_REQUESTED -> "已请求取消，后台将在安全检查点停止。";
            case CANCELLED -> "任务已取消。";
        };
    }
}
