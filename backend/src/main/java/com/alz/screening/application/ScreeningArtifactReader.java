package com.alz.screening.application;

import com.alz.config.StoragePaths;
import com.alz.entity.AudioDiagnosis;
import com.alz.entity.ScreeningRiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
public class ScreeningArtifactReader {

    private final StoragePaths storagePaths;
    private final ObjectMapper objectMapper;

    public ScreeningArtifactReader(StoragePaths storagePaths, ObjectMapper objectMapper) {
        this.storagePaths = storagePaths;
        this.objectMapper = objectMapper;
    }

    public AudioDiagnosis readAnalysis(String taskId, String artifactUri, String expectedSha256) {
        if (!((taskId + "/analysis.json").equals(artifactUri)
                || ("screening-artifacts/" + taskId + "/analysis.json").equals(artifactUri))) {
            throw new IllegalArgumentException("分析 artifact URI 不合法");
        }
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("分析 artifact checksum 不合法");
        }
        Path path = storagePaths.resolveScreeningArtifact(taskId, "analysis.json");
        try {
            byte[] bytes = Files.readAllBytes(path);
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IllegalArgumentException("分析 artifact checksum 不匹配");
            }
            JsonNode root = objectMapper.readTree(bytes);
            AudioDiagnosis diagnosis = new AudioDiagnosis();
            diagnosis.setTranscription(requiredText(root, "transcription"));
            diagnosis.setReport(requiredText(root, "report"));
            diagnosis.setRiskLevel(ScreeningRiskLevel.fromExternal(value(root, "risk_level")));
            JsonNode score = root.path("risk_score");
            if (score.isNumber()) {
                double value = score.doubleValue();
                if (!Double.isFinite(value) || value < 0 || value > 1) {
                    throw new IllegalArgumentException("risk_score 必须在 0 到 1 之间");
                }
                diagnosis.setRiskScore(value);
            }
            JsonNode qualityPassed = root.path("quality_passed");
            diagnosis.setQualityPassed(qualityPassed.isBoolean() ? qualityPassed.booleanValue() : null);
            diagnosis.setQualityIssues(strings(root.path("quality_issues")));
            diagnosis.setFeatureHighlights(strings(root.path("feature_highlights")));
            diagnosis.setModelVersion(requiredText(root, "model_version"));
            return diagnosis;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法读取分析 artifact", exception);
        }
    }

    private String requiredText(JsonNode root, String field) {
        String value = value(root, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("分析 artifact 缺少字段: " + field);
        }
        return value;
    }

    private String value(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                result.add(value.asText());
            }
        });
        return List.copyOf(result);
    }
}
