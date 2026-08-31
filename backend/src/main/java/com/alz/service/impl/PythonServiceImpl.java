package com.alz.service.impl;

import com.alz.entity.AudioDiagnosis;
import com.alz.entity.ScreeningRiskLevel;
import com.alz.exception.ScreeningServiceException;
import com.alz.service.PythonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
public class PythonServiceImpl implements PythonService {

    private final RestTemplate restTemplate;
    private final String pythonApiUrl;

    @Autowired
    public PythonServiceImpl(
            @Value("${app.python.diagnosis-url:http://127.0.0.1:5000/api/diagnosis}") String pythonApiUrl,
            @Value("${app.python.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${app.python.read-timeout-ms:120000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restTemplate = new RestTemplate(requestFactory);
        this.pythonApiUrl = pythonApiUrl;
    }

    PythonServiceImpl(RestTemplate restTemplate, String pythonApiUrl) {
        this.restTemplate = restTemplate;
        this.pythonApiUrl = pythonApiUrl;
    }

    @Override
    public AudioDiagnosis getDiagnosisReport(String audioFilePath) {
        if (audioFilePath == null || audioFilePath.isBlank()) {
            throw new IllegalArgumentException("音频路径不能为空");
        }
        File audioFile = new File(audioFilePath);
        if (!audioFile.isFile()) {
            throw new IllegalArgumentException("音频文件不存在");
        }

        Map<String, String> requestBody = Map.of("audio_path", audioFile.getAbsolutePath());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    pythonApiUrl, requestEntity, Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ScreeningServiceException("语音筛查服务返回异常状态");
            }

            Map<?, ?> body = response.getBody();
            if (body == null) {
                throw new ScreeningServiceException("Python screening service returned an empty response");
            }
            Object remoteError = body.get("error");
            Object remoteMessage = body.get("message");
            if (remoteError != null && !remoteError.toString().isBlank()) {
                throw new ScreeningServiceException("Python screening service error: " + remoteError);
            }
            if (remoteMessage != null && !remoteMessage.toString().isBlank()
                    && (!body.containsKey("transcription") || !body.containsKey("report"))) {
                throw new ScreeningServiceException("Python screening service error: " + remoteMessage);
            }

            Object transcription = body == null ? null : body.get("transcription");
            Object report = body == null ? null : body.get("report");
            if (transcription == null || transcription.toString().isBlank()
                    || report == null || report.toString().isBlank()) {
                throw new ScreeningServiceException("语音筛查服务返回的数据不完整");
            }

            AudioDiagnosis diagnosis = new AudioDiagnosis();
            diagnosis.setFileName(audioFile.getName());
            diagnosis.setTranscription(transcription.toString());
            diagnosis.setReport(report.toString());
            diagnosis.setRiskLevel(ScreeningRiskLevel.fromExternal(body.get("risk_level")));
            diagnosis.setRiskScore(parseRiskScore(body.get("risk_score")));
            diagnosis.setQualityPassed(parseBoolean(body.get("quality_passed")));
            diagnosis.setQualityIssues(parseStringList(body.get("quality_issues")));
            diagnosis.setFeatureHighlights(parseStringList(body.get("feature_highlights")));
            Object modelVersion = body.get("model_version");
            diagnosis.setModelVersion(modelVersion == null ? "legacy-python-diagnosis" : modelVersion.toString());
            return diagnosis;
        } catch (HttpStatusCodeException exception) {
            throw new ScreeningServiceException(
                    "语音筛查服务响应失败（HTTP " + exception.getStatusCode().value() + "）", exception);
        } catch (ResourceAccessException exception) {
            throw new ScreeningServiceException("语音筛查服务连接失败或处理超时", exception);
        }
    }

    private Double parseRiskScore(Object value) {
        if (value == null) {
            return null;
        }
        try {
            double score = value instanceof Number number
                    ? number.doubleValue()
                    : Double.parseDouble(value.toString());
            if (!Double.isFinite(score) || score < 0 || score > 1) {
                throw new ScreeningServiceException("risk_score 必须在 0 到 1 之间");
            }
            return score;
        } catch (NumberFormatException exception) {
            throw new ScreeningServiceException("risk_score 格式错误", exception);
        }
    }

    private Boolean parseBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return null;
    }

    private List<String> parseStringList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
                .filter(item -> item != null && !item.toString().isBlank())
                .map(Object::toString)
                .toList();
    }
}
