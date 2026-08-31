package com.alz.service;

import com.alz.config.StoragePaths;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;

@Service
public class ScreeningDataDeletionService {

    private final AudioService audioService;
    private final DiagnosisReportService diagnosisReportService;
    private final PdfReportService pdfReportService;
    private final StoragePaths storagePaths;

    public ScreeningDataDeletionService(AudioService audioService,
                                        DiagnosisReportService diagnosisReportService,
                                        PdfReportService pdfReportService,
                                        StoragePaths storagePaths) {
        this.audioService = audioService;
        this.diagnosisReportService = diagnosisReportService;
        this.pdfReportService = pdfReportService;
        this.storagePaths = storagePaths;
    }

    @Transactional
    public boolean deleteOwned(String audioName, Long userId) throws IOException {
        if (!audioService.belongsToUser(audioName, userId)) {
            return false;
        }

        String pdfName = pdfReportService.findPdfNameByAudio(audioName);
        Files.deleteIfExists(storagePaths.resolveAudio(audioName));
        if (pdfName != null && !pdfName.isBlank()) {
            Files.deleteIfExists(storagePaths.resolvePdf(pdfName));
        }

        diagnosisReportService.deleteByAudio(audioName);
        pdfReportService.deletepdfByAudio(audioName);
        return audioService.deleteOwned(audioName, userId);
    }
}
