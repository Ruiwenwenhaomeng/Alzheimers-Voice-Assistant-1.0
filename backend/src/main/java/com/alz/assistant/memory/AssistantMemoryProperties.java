package com.alz.assistant.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rag.memory")
public class AssistantMemoryProperties {
    private int maxTurns = 100;
    private int summaryInterval = 10;
    private int rollingSummaryMaxChars = 1800;
    private int cacheTtlMinutes = 1440;
    private int generationTimeoutSeconds = 180;

    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = Math.max(1, Math.min(maxTurns, 100)); }
    public int getSummaryInterval() { return summaryInterval; }
    public void setSummaryInterval(int summaryInterval) { this.summaryInterval = Math.max(1, summaryInterval); }
    public int getRollingSummaryMaxChars() { return rollingSummaryMaxChars; }
    public void setRollingSummaryMaxChars(int value) { this.rollingSummaryMaxChars = Math.max(500, value); }
    public int getCacheTtlMinutes() { return cacheTtlMinutes; }
    public void setCacheTtlMinutes(int value) { this.cacheTtlMinutes = Math.max(1, value); }
    public int getGenerationTimeoutSeconds() { return generationTimeoutSeconds; }
    public void setGenerationTimeoutSeconds(int value) { this.generationTimeoutSeconds = Math.max(30, value); }
}
