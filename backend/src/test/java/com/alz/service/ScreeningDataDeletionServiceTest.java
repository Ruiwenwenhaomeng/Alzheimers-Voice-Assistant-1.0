package com.alz.service;

import com.alz.config.StoragePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScreeningDataDeletionServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void deletesOwnedAudioAndEveryDerivedArtifact() throws Exception {
        AudioService audioService = mock(AudioService.class);
        DiagnosisReportService reportService = mock(DiagnosisReportService.class);
        PdfReportService pdfService = mock(PdfReportService.class);
        StoragePaths paths = paths();
        Files.createDirectories(paths.audioDirectory());
        Files.createDirectories(paths.pdfDirectory());
        Files.write(paths.resolveAudio("sample.wav"), new byte[]{1, 2, 3});
        Files.write(paths.resolvePdf("sample_report.pdf"), new byte[]{4, 5, 6});
        when(audioService.belongsToUser("sample.wav", 9L)).thenReturn(true);
        when(audioService.deleteOwned("sample.wav", 9L)).thenReturn(true);
        when(pdfService.findPdfNameByAudio("sample.wav")).thenReturn("sample_report.pdf");
        ScreeningDataDeletionService service = new ScreeningDataDeletionService(
                audioService, reportService, pdfService, paths);

        assertTrue(service.deleteOwned("sample.wav", 9L));

        assertFalse(Files.exists(paths.resolveAudio("sample.wav")));
        assertFalse(Files.exists(paths.resolvePdf("sample_report.pdf")));
        verify(reportService).deleteByAudio("sample.wav");
        verify(pdfService).deletepdfByAudio("sample.wav");
        verify(audioService).deleteOwned("sample.wav", 9L);
    }

    @Test
    void doesNotTouchArtifactsOwnedByAnotherUser() throws Exception {
        AudioService audioService = mock(AudioService.class);
        DiagnosisReportService reportService = mock(DiagnosisReportService.class);
        PdfReportService pdfService = mock(PdfReportService.class);
        when(audioService.belongsToUser("sample.wav", 9L)).thenReturn(false);
        ScreeningDataDeletionService service = new ScreeningDataDeletionService(
                audioService, reportService, pdfService, paths());

        assertFalse(service.deleteOwned("sample.wav", 9L));

        verifyNoInteractions(reportService, pdfService);
    }

    private StoragePaths paths() {
        return new StoragePaths(
                tempDirectory.resolve("audio").toString(),
                tempDirectory.resolve("pdf").toString(),
                tempDirectory.resolve("admin").toString());
    }
}
