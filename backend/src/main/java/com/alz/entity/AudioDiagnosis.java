package com.alz.entity;

import java.util.List;

public class AudioDiagnosis {

    private String transcription;
    private String report;
    private String fileName;
    private ScreeningRiskLevel riskLevel = ScreeningRiskLevel.INCONCLUSIVE;
    private Double riskScore;
    private Boolean qualityPassed;
    private List<String> qualityIssues = List.of();
    private List<String> featureHighlights = List.of();
    private String modelVersion;

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
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
}
