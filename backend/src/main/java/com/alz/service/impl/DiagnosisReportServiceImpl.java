package com.alz.service.impl;

import com.alz.config.ScreeningConsentPolicy;
import com.alz.config.ScreeningResultPolicy;
import com.alz.config.StoragePaths;
import com.alz.entity.AudioDiagnosis;
import com.alz.entity.DiagnosisReport;
import com.alz.mapper.DiagnosisReportMapper;
import com.alz.mapper.PdfReportMapper;
import com.alz.service.DiagnosisReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.List;
import java.util.UUID;

@Service
public class DiagnosisReportServiceImpl implements DiagnosisReportService {

    @Autowired
    private DiagnosisReportMapper reportMapper;

    @Autowired
    private PdfReportMapper pdfMapper;

    @Autowired
    private StoragePaths storagePaths;

    @Override
    @Transactional
    public void saveReport(Long userId,
                           String audioName,
                           String transcription,
                           String report) {
        AudioDiagnosis diagnosis = new AudioDiagnosis();
        diagnosis.setTranscription(transcription);
        diagnosis.setReport(report);
        saveReport(userId, audioName, UUID.randomUUID().toString(), diagnosis);
    }

    @Override
    @Transactional
    public void saveReport(Long userId, String audioName, String screeningId,
                           AudioDiagnosis diagnosis) {

        saveReportInternal(userId, audioName, screeningId, null, diagnosis, false);
    }

    @Override
    @Transactional
    public void saveReportForTask(Long userId, String audioName, String taskId,
                                  AudioDiagnosis diagnosis) {
        saveReportInternal(userId, audioName, taskId, taskId, diagnosis, true);
    }

    private void saveReportInternal(Long userId, String audioName, String screeningId,
                                    String screeningTaskId, AudioDiagnosis diagnosis,
                                    boolean idempotent) {

        if (idempotent) {
            Long taskExisting = reportMapper.countByScreeningTaskId(screeningTaskId);
            if (taskExisting != null && taskExisting > 0) {
                return;
            }
        }

        // 检查音频是否已经做过检测
        Long existing = reportMapper.countByAudioName(audioName);
        if (existing != null && existing > 0) {
            if (idempotent) {
                return;
            }
            throw new RuntimeException("本音频已经检测过，请重新选择");
        }

        // 查询当前用户的报告数量
        Long count = reportMapper.countByUser(userId);

        // 限制为最多保留10条，超过时删除最旧的一条
        if (count >= 10) {
            // 找到最旧的报告
            String oldAudio = reportMapper.findOldestAudioName(userId);

            // 删除诊断报告数据库记录
            reportMapper.deleteOldest(userId);

            // 删除对应的PDF文件及数据库记录（如果存在）
            String pdfName = pdfMapper.findPdfByAudio(oldAudio);
            if (pdfName != null) {
                File file = storagePaths.resolvePdf(pdfName).toFile();
                if (file.exists()) {
                    file.delete();
                }
                pdfMapper.deleteByAudio(oldAudio);
            }
        }

        // 插入新报告
        DiagnosisReport report = new DiagnosisReport();
        report.setUserId(userId);
        report.setAudioName(audioName);
        report.setTranscription(diagnosis.getTranscription());
        report.setReport(diagnosis.getReport());
        report.setScreeningId(screeningId);
        report.setScreeningTaskId(screeningTaskId);
        report.setScreeningStatus(ScreeningResultPolicy.statusFor(diagnosis));
        report.setRiskLevel(diagnosis.getRiskLevel());
        report.setRiskScore(diagnosis.getRiskScore());
        report.setQualityPassed(diagnosis.getQualityPassed());
        report.setQualityIssues(diagnosis.getQualityIssues());
        report.setFeatureHighlights(diagnosis.getFeatureHighlights());
        report.setModelVersion(diagnosis.getModelVersion());
        report.setDisclaimerVersion(ScreeningConsentPolicy.DISCLAIMER_VERSION);
        reportMapper.insertReport(report);
    }

    @Override
    public List<DiagnosisReport> listByUser(Long userId) {
        return reportMapper.listByUser(userId);
    }

    @Override
    public DiagnosisReport findByAudioName(String audioName) {
        return reportMapper.findByAudioName(audioName);
    }

    @Override
    public Long countByAudioName(String audioName) {
        return reportMapper.countByAudioName(audioName);
    }

    @Override
    public void deleteByAudio(String audioName) {
        reportMapper.deleteByAudio(audioName);
    }

    @Override
    public DiagnosisReport findByScreeningTaskId(String taskId) {
        return reportMapper.findByScreeningTaskId(taskId);
    }
}
