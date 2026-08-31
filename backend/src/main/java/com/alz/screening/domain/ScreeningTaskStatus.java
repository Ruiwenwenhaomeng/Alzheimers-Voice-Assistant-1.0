package com.alz.screening.domain;

import java.util.EnumSet;
import java.util.Set;

public enum ScreeningTaskStatus {
    QUEUED,
    TRANSCRIBING,
    FEATURE_EXTRACTING,
    LLM_ANALYZING,
    RESULT_PERSISTING,
    PDF_QUEUED,
    PDF_GENERATING,
    COMPLETED,
    RETRY_WAIT,
    FAILED,
    CANCEL_REQUESTED,
    CANCELLED;

    private static final Set<ScreeningTaskStatus> ACTIVE = EnumSet.of(
            QUEUED, TRANSCRIBING, FEATURE_EXTRACTING, LLM_ANALYZING,
            RESULT_PERSISTING, PDF_QUEUED, PDF_GENERATING, RETRY_WAIT,
            CANCEL_REQUESTED
    );

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
