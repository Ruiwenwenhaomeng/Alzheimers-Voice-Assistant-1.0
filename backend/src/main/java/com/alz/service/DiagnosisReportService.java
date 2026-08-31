package com.alz.service;

import com.alz.entity.DiagnosisReport;
import com.alz.entity.AudioDiagnosis;

import java.util.List;

public interface DiagnosisReportService {
    void saveReport(Long userId,
                    String audioName,
                    String transcription,
                    String report);
    void saveReport(Long userId, String audioName, String screeningId, AudioDiagnosis diagnosis);
    void saveReportForTask(Long userId, String audioName, String taskId, AudioDiagnosis diagnosis);
    public List<DiagnosisReport> listByUser(Long userId);
    DiagnosisReport findByAudioName(String audioName);
    Long countByAudioName(String audioName);
    void deleteByAudio(String audioName);
    DiagnosisReport findByScreeningTaskId(String taskId);
}
