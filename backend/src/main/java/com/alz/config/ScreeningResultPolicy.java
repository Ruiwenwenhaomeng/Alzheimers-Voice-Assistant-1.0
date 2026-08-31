package com.alz.config;

import com.alz.entity.AudioDiagnosis;
import com.alz.entity.ScreeningRiskLevel;

public final class ScreeningResultPolicy {

    public static final String COMPLETED = "COMPLETED";
    public static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    public static final String MEDICAL_DISCLAIMER =
            "语音结果仅用于认知风险提示，不能诊断或排除阿尔茨海默病。";

    private ScreeningResultPolicy() {
    }

    public static String statusFor(AudioDiagnosis diagnosis) {
        boolean complete = diagnosis != null
                && Boolean.TRUE.equals(diagnosis.getQualityPassed())
                && diagnosis.getRiskLevel() != null
                && diagnosis.getRiskLevel() != ScreeningRiskLevel.INCONCLUSIVE
                && diagnosis.getModelVersion() != null
                && !diagnosis.getModelVersion().isBlank();
        return complete ? COMPLETED : REVIEW_REQUIRED;
    }
}
