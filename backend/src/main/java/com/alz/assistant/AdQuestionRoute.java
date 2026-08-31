package com.alz.assistant;

import java.util.List;

public record AdQuestionRoute(
        AdQuestionCategory category,
        double confidence,
        boolean urgent,
        List<String> matchedSignals,
        String reason
) {
}
