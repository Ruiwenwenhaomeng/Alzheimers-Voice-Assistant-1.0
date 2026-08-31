package com.alz.screening.pdf;

import com.alz.config.ScreeningResultPolicy;
import com.alz.config.StoragePaths;
import com.alz.entity.DiagnosisReport;
import com.alz.screening.domain.ScreeningTask;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class ScreeningPdfGenerator {

    private final StoragePaths storagePaths;

    public ScreeningPdfGenerator(StoragePaths storagePaths) {
        this.storagePaths = storagePaths;
    }

    public GeneratedPdf generate(ScreeningTask task, DiagnosisReport report) {
        String pdfName = task.getId() + "_report.pdf";
        String temporaryName = task.getId() + ".pdf.tmp";
        Path finalPath = storagePaths.resolvePdf(pdfName);
        Path temporaryPath = storagePaths.resolvePdf(temporaryName);
        try {
            Files.createDirectories(storagePaths.pdfDirectory());
            Files.deleteIfExists(temporaryPath);
            byte[] fontBytes = new ClassPathResource("fonts/SanJiHuaChaoTi-Cu-2.ttf")
                    .getInputStream().readAllBytes();
            try (PdfWriter writer = new PdfWriter(temporaryPath.toString());
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                PdfFont font = PdfFontFactory.createFont(
                        fontBytes, PdfEncodings.IDENTITY_H,
                        PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
                document.setFont(font);
                document.add(new Paragraph("阿尔茨海默病语音风险筛查报告"));
                document.add(new Paragraph("任务编号: " + task.getId()));
                document.add(new Paragraph("音频文件: " + task.getAudioName()));
                document.add(new Paragraph("筛查状态: " + report.getScreeningStatus()));
                document.add(new Paragraph("风险等级: " + report.getRiskLevel()));
                document.add(new Paragraph("转录文本:"));
                document.add(new Paragraph(report.getTranscription()));
                document.add(new Paragraph("筛查报告:"));
                document.add(new Paragraph(report.getReport()));
                document.add(new Paragraph("医疗边界:"));
                document.add(new Paragraph(ScreeningResultPolicy.MEDICAL_DISCLAIMER));
            }
            try {
                Files.move(temporaryPath, finalPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
            byte[] bytes = Files.readAllBytes(finalPath);
            String sha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            return new GeneratedPdf(pdfName, sha256, (long) bytes.length);
        } catch (IOException | NoSuchAlgorithmException exception) {
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException ignored) {
                // The reconciliation job can remove orphaned temporary files.
            }
            throw new IllegalStateException("PDF 生成失败", exception);
        }
    }

    public record GeneratedPdf(String pdfName, String sha256, long size) { }
}
