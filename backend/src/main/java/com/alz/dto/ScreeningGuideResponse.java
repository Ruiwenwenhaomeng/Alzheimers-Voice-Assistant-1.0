package com.alz.dto;

import java.util.List;

public record ScreeningGuideResponse(
        String title,
        String purpose,
        String medicalBoundary,
        List<String> beforeRecording,
        List<ScreeningTask> tasks,
        List<String> resultConsiderations,
        List<String> stopAndSeekHelpWhen,
        String privacyNotice
) {
    public record ScreeningTask(
            int order,
            String name,
            String instruction,
            String suggestedDuration
    ) {
    }
}
