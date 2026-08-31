package com.alz.entity;

import java.time.LocalDateTime;
import java.util.List;

public class DiagnosisReport {

    private Long id;

    private Long userId;

    private String audioName;

    private String transcription;

    private String report;

    private LocalDateTime createTime;

    private String screeningId;

    private String screeningTaskId;

    private String screeningStatus;

    private ScreeningRiskLevel riskLevel = ScreeningRiskLevel.INCONCLUSIVE;

    private Double riskScore;

    private Boolean qualityPassed;

    private List<String> qualityIssues = List.of();

    private List<String> featureHighlights = List.of();

    private String modelVersion;

    private String disclaimerVersion;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getAudioName() {
        return audioName;
    }

    public void setAudioName(String audioName) {
        this.audioName = audioName;
    }

    public String getTranscription() {
        return transcription;
    }

    public void setTranscription(String transcription) {
        this.transcription = transcription;
    }

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public String getScreeningId() {
        return screeningId;
    }

    public void setScreeningId(String screeningId) {
        this.screeningId = screeningId;
    }

    public String getScreeningTaskId() {
        return screeningTaskId;
    }

    public void setScreeningTaskId(String screeningTaskId) {
        this.screeningTaskId = screeningTaskId;
    }

    public String getScreeningStatus() {
        return screeningStatus;
    }

    public void setScreeningStatus(String screeningStatus) {
        this.screeningStatus = screeningStatus;
    }

    public ScreeningRiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(ScreeningRiskLevel riskLevel) {
        this.riskLevel = riskLevel == null ? ScreeningRiskLevel.INCONCLUSIVE : riskLevel;
    }

    public Double getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Double riskScore) {
        this.riskScore = riskScore;
    }

    public Boolean getQualityPassed() {
        return qualityPassed;
    }

    public void setQualityPassed(Boolean qualityPassed) {
        this.qualityPassed = qualityPassed;
    }

    public List<String> getQualityIssues() {
        return qualityIssues;
    }

    public void setQualityIssues(List<String> qualityIssues) {
        this.qualityIssues = qualityIssues == null ? List.of() : List.copyOf(qualityIssues);
    }

    public List<String> getFeatureHighlights() {
        return featureHighlights;
    }

    public void setFeatureHighlights(List<String> featureHighlights) {
        this.featureHighlights = featureHighlights == null ? List.of() : List.copyOf(featureHighlights);
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getDisclaimerVersion() {
        return disclaimerVersion;
    }

    public void setDisclaimerVersion(String disclaimerVersion) {
        this.disclaimerVersion = disclaimerVersion;
    }

}
