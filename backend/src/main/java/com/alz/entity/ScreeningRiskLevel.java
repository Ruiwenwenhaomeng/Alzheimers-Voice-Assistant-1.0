package com.alz.entity;

import java.util.Locale;

public enum ScreeningRiskLevel {
    LOW,
    ELEVATED,
    HIGH,
    INCONCLUSIVE;

    public static ScreeningRiskLevel fromExternal(Object value) {
        if (value == null) {
            return INCONCLUSIVE;
        }
        try {
            return valueOf(value.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return INCONCLUSIVE;
        }
    }
}
