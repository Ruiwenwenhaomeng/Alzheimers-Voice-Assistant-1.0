package com.alz.service.impl;

import com.alz.entity.PdfReport;
import com.alz.mapper.PdfReportMapper;
import com.alz.service.PdfReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class PdfReportServiceImpl implements PdfReportService {

    @Autowired
    private PdfReportMapper mapper;

    @Override
    public void save(PdfReport report) {
        mapper.insert(report);
    }

    @Override
    public List<PdfReport> listByUser(Long userId) {
        return mapper.listByUser(userId);
    }

    @Override
    public PdfReport findByPdfName(String pdfName) {
        return mapper.findByPdfName(pdfName);
    }

    @Override
    public String findPdfNameByAudio(String audioName) {
        return mapper.findPdfByAudio(audioName);
    }

    @Override
    public void deletepdfByAudio(String audioName) {
        mapper.deletepdfByAudio(audioName);
    }

    @Override
    public PdfReport findByScreeningTaskId(String taskId) {
        return mapper.findByScreeningTaskId(taskId);
    }
}
