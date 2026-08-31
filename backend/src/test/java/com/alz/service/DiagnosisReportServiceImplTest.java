package com.alz.service;

import com.alz.config.ScreeningConsentPolicy;
import com.alz.entity.AudioDiagnosis;
import com.alz.entity.DiagnosisReport;
import com.alz.entity.ScreeningRiskLevel;
import com.alz.mapper.DiagnosisReportMapper;
import com.alz.mapper.PdfReportMapper;
import com.alz.service.impl.DiagnosisReportServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiagnosisReportServiceImplTest {

    @Mock
    private DiagnosisReportMapper reportMapper;

    @Mock
    private PdfReportMapper pdfMapper;

    @InjectMocks
    private DiagnosisReportServiceImpl service;

    @Test
    void persistsTheCompleteScreeningAuditRecord() {
        when(reportMapper.countByAudioName("audio.wav")).thenReturn(0L);
        when(reportMapper.countByUser(7L)).thenReturn(0L);
        AudioDiagnosis diagnosis = new AudioDiagnosis();
        diagnosis.setTranscription("今天去公园散步");
        diagnosis.setReport("语音质量合格，建议结合后续评估。");
        diagnosis.setRiskLevel(ScreeningRiskLevel.ELEVATED);
        diagnosis.setRiskScore(0.62);
        diagnosis.setQualityPassed(true);
        diagnosis.setQualityIssues(List.of());
        diagnosis.setFeatureHighlights(List.of("停顿比例偏高"));
        diagnosis.setModelVersion("research-model-2026-07");

        service.saveReport(7L, "audio.wav", "screening-123", diagnosis);

        ArgumentCaptor<DiagnosisReport> captor = ArgumentCaptor.forClass(DiagnosisReport.class);
        verify(reportMapper).insertReport(captor.capture());
        DiagnosisReport saved = captor.getValue();
        assertEquals("screening-123", saved.getScreeningId());
        assertEquals("COMPLETED", saved.getScreeningStatus());
        assertEquals(ScreeningRiskLevel.ELEVATED, saved.getRiskLevel());
        assertEquals(0.62, saved.getRiskScore());
        assertEquals(List.of("停顿比例偏高"), saved.getFeatureHighlights());
        assertEquals("research-model-2026-07", saved.getModelVersion());
        assertEquals(ScreeningConsentPolicy.DISCLAIMER_VERSION, saved.getDisclaimerVersion());
    }

    @Test
    void marksQualityFailureAsReviewRequired() {
        when(reportMapper.countByAudioName("noisy.wav")).thenReturn(0L);
        when(reportMapper.countByUser(7L)).thenReturn(0L);
        AudioDiagnosis diagnosis = new AudioDiagnosis();
        diagnosis.setTranscription("[转写不可用]");
        diagnosis.setReport("环境噪声过高，请重新录制。");
        diagnosis.setRiskLevel(ScreeningRiskLevel.INCONCLUSIVE);
        diagnosis.setQualityPassed(false);
        diagnosis.setQualityIssues(List.of("环境噪声过高"));

        service.saveReport(7L, "noisy.wav", "screening-456", diagnosis);

        ArgumentCaptor<DiagnosisReport> captor = ArgumentCaptor.forClass(DiagnosisReport.class);
        verify(reportMapper).insertReport(captor.capture());
        assertEquals("REVIEW_REQUIRED", captor.getValue().getScreeningStatus());
        assertEquals(List.of("环境噪声过高"), captor.getValue().getQualityIssues());
    }

    @Test
    void requiresAModelVersionBeforeCallingAResultComplete() {
        when(reportMapper.countByAudioName("unversioned.wav")).thenReturn(0L);
        when(reportMapper.countByUser(7L)).thenReturn(0L);
        AudioDiagnosis diagnosis = new AudioDiagnosis();
        diagnosis.setTranscription("测试文本");
        diagnosis.setReport("旧服务返回的结果");
        diagnosis.setRiskLevel(ScreeningRiskLevel.LOW);
        diagnosis.setQualityPassed(true);

        service.saveReport(7L, "unversioned.wav", "screening-789", diagnosis);

        ArgumentCaptor<DiagnosisReport> captor = ArgumentCaptor.forClass(DiagnosisReport.class);
        verify(reportMapper).insertReport(captor.capture());
        assertEquals("REVIEW_REQUIRED", captor.getValue().getScreeningStatus());
    }
}
