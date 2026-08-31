package com.alz.assistant.memory;

import java.time.LocalDateTime;

public class AssistantMessage {
    private Long id;
    private String conversationId;
    private int turnNo;
    private String role;
    private String content;
    private String title;
    private String intent;
    private boolean urgent;
    private String metadataJson;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public int getTurnNo() { return turnNo; }
    public void setTurnNo(int turnNo) { this.turnNo = turnNo; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public boolean isUrgent() { return urgent; }
    public void setUrgent(boolean urgent) { this.urgent = urgent; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
