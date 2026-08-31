package com.alz.service.impl;

import com.alz.entity.AudioDiagnosis;
import com.alz.entity.ScreeningRiskLevel;
import com.alz.exception.ScreeningServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PythonServiceImplTest {

    private static final String API_URL = "http://screening.test/api/diagnosis";

    @TempDir
    Path tempDir;

    private MockRestServiceServer server;
    private PythonServiceImpl service;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        service = new PythonServiceImpl(restTemplate, API_URL);
    }

    @Test
    void returnsValidatedScreeningResponse() throws Exception {
        Path audio = Files.write(tempDir.resolve("sample.wav"), new byte[]{1, 2, 3});
        server.expect(once(), requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.audio_path").isNotEmpty())
                .andRespond(withSuccess(
                        "{\"transcription\":\"测试文本\",\"report\":\"风险提示\"," +
                                "\"risk_level\":\"ELEVATED\",\"risk_score\":0.68," +
                                "\"quality_passed\":true,\"quality_issues\":[]," +
                                "\"feature_highlights\":[\"停顿比例偏高\"]," +
                                "\"model_version\":\"test-v1\"}",
                        MediaType.APPLICATION_JSON));

        AudioDiagnosis result = service.getDiagnosisReport(audio.toString());

        assertEquals("sample.wav", result.getFileName());
        assertEquals("测试文本", result.getTranscription());
        assertEquals("风险提示", result.getReport());
        assertEquals(ScreeningRiskLevel.ELEVATED, result.getRiskLevel());
        assertEquals(0.68, result.getRiskScore());
        assertEquals(true, result.getQualityPassed());
        assertEquals("停顿比例偏高", result.getFeatureHighlights().get(0));
        assertEquals("test-v1", result.getModelVersion());
        server.verify();
    }

    @Test
    void rejectsIncompleteResponse() throws Exception {
        Path audio = Files.write(tempDir.resolve("sample.wav"), new byte[]{1});
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("{\"transcription\":\"测试文本\"}", MediaType.APPLICATION_JSON));

        assertThrows(ScreeningServiceException.class,
                () -> service.getDiagnosisReport(audio.toString()));
    }

    @Test
    void convertsRemoteFailureToDomainException() throws Exception {
        Path audio = Files.write(tempDir.resolve("sample.wav"), new byte[]{1});
        server.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(ScreeningServiceException.class,
                () -> service.getDiagnosisReport(audio.toString()));
    }

    @Test
    void rejectsRiskScoreOutsideContractRange() throws Exception {
        Path audio = Files.write(tempDir.resolve("sample.wav"), new byte[]{1});
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(
                        "{\"transcription\":\"测试文本\",\"report\":\"风险提示\"," +
                                "\"risk_score\":1.5}", MediaType.APPLICATION_JSON));

        assertThrows(ScreeningServiceException.class,
                () -> service.getDiagnosisReport(audio.toString()));
    }
}
