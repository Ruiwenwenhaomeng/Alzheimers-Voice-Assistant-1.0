package com.alz.assistant.memory;

import java.time.LocalDateTime;

public class AssistantConversation {
    private String id;
    private String ownerType;
    private String ownerKey;
    private String title;
    private int userTurnCount;
    private int summaryUpToTurn;
    private String rollingSummary;
    private String generationStatus;
    private LocalDateTime generationStartedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }
    public String getOwnerKey() { return ownerKey; }
    public void setOwnerKey(String ownerKey) { this.ownerKey = ownerKey; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getUserTurnCount() { return userTurnCount; }
    public void setUserTurnCount(int userTurnCount) { this.userTurnCount = userTurnCount; }
    public int getSummaryUpToTurn() { return summaryUpToTurn; }
    public void setSummaryUpToTurn(int summaryUpToTurn) { this.summaryUpToTurn = summaryUpToTurn; }
    public String getRollingSummary() { return rollingSummary; }
    public void setRollingSummary(String rollingSummary) { this.rollingSummary = rollingSummary; }
    public String getGenerationStatus() { return generationStatus; }
    public void setGenerationStatus(String generationStatus) { this.generationStatus = generationStatus; }
    public LocalDateTime getGenerationStartedAt() { return generationStartedAt; }
    public void setGenerationStartedAt(LocalDateTime generationStartedAt) { this.generationStartedAt = generationStartedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
