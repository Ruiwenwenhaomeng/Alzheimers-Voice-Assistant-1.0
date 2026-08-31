package com.alz.screening.application;

import com.alz.config.StoragePaths;
import com.alz.entity.AudioDiagnosis;
import com.alz.entity.ScreeningRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScreeningArtifactReaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void verifiesChecksumAndReadsSnakeCaseAnalysis() throws Exception {
        StoragePaths paths = new StoragePaths(
                tempDirectory.resolve("audio").toString(),
                tempDirectory.resolve("pdf").toString(),
                tempDirectory.resolve("admin").toString(),
                tempDirectory.resolve("artifacts").toString());
        String taskId = "62f7d621-295d-4d43-83f1-09ea40196a1a";
        Path artifact = paths.resolveScreeningArtifact(taskId, "analysis.json");
        Files.createDirectories(artifact.getParent());
        byte[] json = """
                {"transcription":"测试转录","report":"谨慎的筛查报告", "risk_level":"ELEVATED",
                 "risk_score":0.62,"quality_passed":true,"quality_issues":[],
                 "feature_highlights":["TTR=0.5"],"model_version":"model-v1"}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(artifact, json);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));

        AudioDiagnosis diagnosis = new ScreeningArtifactReader(paths, new ObjectMapper())
                .readAnalysis(taskId, taskId + "/analysis.json", sha);

        assertEquals("测试转录", diagnosis.getTranscription());
        assertEquals(ScreeningRiskLevel.ELEVATED, diagnosis.getRiskLevel());
        assertEquals(0.62, diagnosis.getRiskScore());
    }
}
