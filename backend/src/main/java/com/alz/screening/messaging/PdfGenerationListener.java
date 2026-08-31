package com.alz.screening.messaging;

import com.alz.entity.DiagnosisReport;
import com.alz.entity.PdfReport;
import com.alz.screening.application.ScreeningEventFactory;
import com.alz.screening.domain.ScreeningEvent;
import com.alz.screening.domain.ScreeningTask;
import com.alz.screening.domain.ScreeningTaskStatus;
import com.alz.screening.pdf.ScreeningPdfGenerator;
import com.alz.screening.persistence.ConsumedEventMapper;
import com.alz.screening.persistence.OutboxEventMapper;
import com.alz.screening.persistence.ScreeningTaskMapper;
import com.alz.service.DiagnosisReportService;
import com.alz.service.PdfReportService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.screening.async", name = "enabled", havingValue = "true")
public class PdfGenerationListener {

    private static final String CONSUMER = "java-pdf-v1";

    private final ScreeningTaskMapper taskMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final DiagnosisReportService diagnosisReportService;
    private final PdfReportService pdfReportService;
    private final ScreeningPdfGenerator pdfGenerator;
    private final ScreeningEventFactory eventFactory;
    private final OutboxEventMapper outboxMapper;

    public PdfGenerationListener(ScreeningTaskMapper taskMapper,
                                 ConsumedEventMapper consumedEventMapper,
                                 DiagnosisReportService diagnosisReportService,
                                 PdfReportService pdfReportService,
                                 ScreeningPdfGenerator pdfGenerator,
                                 ScreeningEventFactory eventFactory,
                                 OutboxEventMapper outboxMapper) {
        this.taskMapper = taskMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.diagnosisReportService = diagnosisReportService;
        this.pdfReportService = pdfReportService;
        this.pdfGenerator = pdfGenerator;
        this.eventFactory = eventFactory;
        this.outboxMapper = outboxMapper;
    }

    @Transactional
    @RabbitListener(queues = ScreeningMessagingConfig.PDF_QUEUE,
            containerFactory = "screeningPdfContainerFactory")
    public void generate(ScreeningEvent event) {
        validate(event);
        if (consumedEventMapper.claim(CONSUMER, event.eventId()) == 0) {
            return;
        }
        ScreeningTask task = taskMapper.findById(event.taskId());
        if (task == null || !task.getUserId().equals(event.userId())
                || !task.getAudioRecordId().equals(event.audioId())) {
            throw new IllegalArgumentException("PDF 事件与筛查任务不匹配");
        }
        if (task.getAttemptCount() == null || task.getAttemptCount() != event.attempt()) {
            return;
        }
        if (task.getStatus() == ScreeningTaskStatus.CANCEL_REQUESTED) {
            taskMapper.markCancelled(task.getId(), event.attempt());
            return;
        }
        if (task.getStatus().isTerminal()) {
            return;
        }

        taskMapper.advance(task.getId(), event.attempt(), "PDF_GENERATING", "PDF", 90);
        DiagnosisReport diagnosis = diagnosisReportService.findByScreeningTaskId(task.getId());
        if (diagnosis == null) {
            throw new IllegalStateException("筛查结果不存在，不能生成 PDF");
        }
        ScreeningPdfGenerator.GeneratedPdf generated = pdfGenerator.generate(task, diagnosis);
        PdfReport pdf = new PdfReport();
        pdf.setScreeningTaskId(task.getId());
        pdf.setUserId(task.getUserId());
        pdf.setAudioName(task.getAudioName());
        pdf.setPdfName(generated.pdfName());
        pdf.setFileSha256(generated.sha256());
        pdf.setFileSize(generated.size());
        pdfReportService.save(pdf);
        taskMapper.markCompleted(task.getId(), event.attempt());

        ScreeningEvent completed = eventFactory.event(
                eventFactory.deterministicEventId(task.getId(), "pdf-completed", event.attempt()),
                "pdf.completed.v1", task.getId(), task.getUserId(), task.getAudioRecordId(),
                task.getTraceId(), event.attempt(),
                Map.of("pdfName", generated.pdfName(), "sha256", generated.sha256())
        );
        outboxMapper.insert(eventFactory.outbox(completed));
    }

    private void validate(ScreeningEvent event) {
        if (event == null || !"pdf.requested.v1".equals(event.eventType())
                || event.schemaVersion() != 1 || event.eventId() == null || event.taskId() == null
                || event.userId() == null || event.audioId() == null) {
            throw new IllegalArgumentException("PDF 生成事件不合法");
        }
    }
}
