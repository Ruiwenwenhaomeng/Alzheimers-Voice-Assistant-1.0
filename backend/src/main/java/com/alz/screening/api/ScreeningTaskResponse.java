package com.alz.screening.api;

import com.alz.entity.DiagnosisReport;

import java.time.LocalDateTime;
import java.util.Map;

public record ScreeningTaskResponse(
        String taskId,
        Long audioId,
        String audioName,
        String status,
        String stage,
        int progress,
        String message,
        String errorCode,
        String errorMessage,
        LocalDateTime requestedAt,
        LocalDateTime updatedAt,
        DiagnosisReport result,
        Map<String, String> links
) {
}
