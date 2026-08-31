package com.alz.service;

import com.alz.entity.PdfReport;
import java.util.List;

public interface PdfReportService {
    void save(PdfReport report);
    public List<PdfReport> listByUser(Long userId);
    PdfReport findByPdfName(String pdfName);
    String findPdfNameByAudio(String audioName);
    void deletepdfByAudio(String audioName);
    PdfReport findByScreeningTaskId(String taskId);
}
