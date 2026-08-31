package com.alz.controller;

import com.alz.entity.AudioDiagnosis;
import com.alz.exception.ScreeningServiceException;
import com.alz.entity.AudioRecord;
import com.alz.entity.User;
import com.alz.service.AudioService;
import com.alz.service.DiagnosisReportService;
import com.alz.service.UserService;
import com.alz.service.PythonService;
import com.alz.config.JwtUtil;
import com.alz.config.AudioMediaTypes;
import com.alz.config.RequireRole;
import com.alz.config.ScreeningConsentPolicy;
import com.alz.config.ScreeningResultPolicy;
import com.alz.config.StoragePaths;
import com.alz.entity.DiagnosisReport;
import com.alz.entity.PdfReport;
import com.alz.service.PdfReportService;
import com.alz.service.ScreeningDataDeletionService;
import com.alz.screening.api.ScreeningTaskResponse;
import com.alz.screening.application.ScreeningTaskService;
import com.alz.validation.AudioUploadValidator;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/audio")
@RequireRole("USER")
public class AudioController {

    @Autowired
    private AudioService audioService;

    @Autowired
    private UserService userService;

    @Autowired
    private PythonService pythonService;

    @Autowired
    private DiagnosisReportService reportService;

    @Autowired
    private PdfReportService pdfReportService;

    @Autowired
    private AudioUploadValidator audioUploadValidator;

    @Autowired
    private StoragePaths storagePaths;

    @Autowired
    private ScreeningDataDeletionService screeningDataDeletionService;

    @Autowired
    private ScreeningTaskService screeningTaskService;

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(value = "duration", required = false) Integer ignoredClientDuration,
                         @RequestParam(value = "imageName", defaultValue = "natural-speech") String imageName,
                         @RequestParam(value = "consentAccepted", defaultValue = "false") boolean consentAccepted,
                         @RequestParam(value = "consentVersion", defaultValue = "") String consentVersion,
                         @RequestParam(value = "taskType", defaultValue = ScreeningConsentPolicy.DEFAULT_TASK_TYPE) String taskType,
                         HttpServletRequest request) throws Exception {
        User user = currentUser(request);
        if (!consentAccepted) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须先明确同意语音筛查和敏感健康信息处理");
        }
        if (!ScreeningConsentPolicy.CURRENT_VERSION.equals(consentVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "知情同意版本已更新，请重新阅读并确认");
        }
        if (!ScreeningConsentPolicy.DEFAULT_TASK_TYPE.equals(taskType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持该语音任务类型");
        }

        final int measuredDuration;
        try {
            measuredDuration = audioUploadValidator.validateAndMeasureDuration(file);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }

        Path audioDirectory = storagePaths.audioDirectory();
        Files.createDirectories(audioDirectory);
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = user.getId() + "_" + timestamp + "_" + UUID.randomUUID() + ".wav";
        Path destination = storagePaths.resolveAudio(fileName);
        try {
            file.transferTo(destination);
            audioService.save(user.getId(), fileName, measuredDuration, imageName,
                    consentVersion, taskType);
        } catch (Exception exception) {
            Files.deleteIfExists(destination);
            throw exception;
        }
        return "上传成功";
    }

    // 获取当前用户音频
    @GetMapping("/my")
    public List<AudioRecord> myAudios(HttpServletRequest request) {
        String tokenHeader = request.getHeader("Authorization");
        String token = tokenHeader.substring(7);
        String username = JwtUtil.getUsername(token);
        User user = userService.findByUsername(username);
        return audioService.listByUser(user.getId());
    }

    @DeleteMapping("/{audioId}")
    public Map<String, Object> deleteAudio(@PathVariable String audioId,
                                            HttpServletRequest request) throws Exception {
        User user = currentUser(request);
        if (screeningTaskService.hasActiveForAudio(audioId, user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "音频存在活动筛查任务，请先取消任务");
        }
        if (!screeningDataDeletionService.deleteOwned(audioId, user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "音频不存在");
        }
        return Map.of(
                "deleted", true,
                "audioName", audioId,
                "message", "音频、筛查结果及关联报告已删除"
        );
    }

    // ⭐ 生成诊断报告
    @GetMapping("/diagnosis/{audioId}")
    public Map<String,Object> getDiagnosisReport(
            @PathVariable String audioId,
            HttpServletRequest request){
        String tokenHeader = request.getHeader("Authorization");
        String token = tokenHeader.substring(7);
        String username = JwtUtil.getUsername(token);
        User user = userService.findByUsername(username);
        if (!audioService.belongsToUser(audioId, user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "音频不存在");
        }
        String filePath = audioService.getAudioFilePath(audioId);
        try{
            String taskId = UUID.randomUUID().toString();
            AudioDiagnosis diagnosis =
                    pythonService.getDiagnosisReport(filePath);
            // 保存报告（内部自动处理删除）
            reportService.saveReport(
                    user.getId(),
                    audioId,
                    taskId,
                    diagnosis
            );
            Map<String,Object> res = new HashMap<>();
            res.put("transcription",diagnosis.getTranscription());
            res.put("report",diagnosis.getReport());
            res.put("taskId",taskId);
            res.put("screeningType", "RISK_SCREENING");
            res.put("screeningStatus", ScreeningResultPolicy.statusFor(diagnosis));
            res.put("riskLevel", diagnosis.getRiskLevel());
            res.put("riskScore", diagnosis.getRiskScore());
            res.put("qualityPassed", diagnosis.getQualityPassed());
            res.put("qualityIssues", diagnosis.getQualityIssues());
            res.put("featureHighlights", diagnosis.getFeatureHighlights());
            res.put("modelVersion", diagnosis.getModelVersion());
            res.put("medicalDisclaimer", ScreeningResultPolicy.MEDICAL_DISCLAIMER);
            res.put("recommendedActions", List.of(
                    "结合本人既往状态和多次结果观察变化趋势",
                    "风险升高、症状持续或影响日常生活时，预约记忆门诊、神经内科或老年医学科",
                    "突然出现语言障碍、口角歪斜、单侧无力或意识异常时立即拨打 120"
            ));
            return res;
        } catch (ScreeningServiceException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "语音筛查服务暂时不可用，请稍后重试", exception);
        } catch(Exception e){
            e.printStackTrace();
            throw new RuntimeException("诊断失败");
        }
    }

    @GetMapping("/report/my")
    public List<DiagnosisReport> myReports(HttpServletRequest request){

        String tokenHeader = request.getHeader("Authorization");
        if (tokenHeader == null || !tokenHeader.startsWith("Bearer ")) {
            throw new RuntimeException("未登录");
        }
        String token = tokenHeader.substring(7);
        String username = JwtUtil.getUsername(token);
        User user = userService.findByUsername(username);
        return reportService.listByUser(user.getId());
    }

    @PostMapping("/pdf/save")
    public String savePdf(@RequestBody Map<String,String> data,HttpServletRequest request) throws Exception {
        String audioName = data.get("audioName");
        String pdfName = audioName + "_report.pdf";
        String transcription = data.get("transcription");
        String report = data.get("report");

        // ⭐ 获取当前用户
        String tokenHeader = request.getHeader("Authorization");
        if (tokenHeader == null || !tokenHeader.startsWith("Bearer ")) {
            throw new RuntimeException("未登录");
        }
        String token = tokenHeader.substring(7);
        String username = JwtUtil.getUsername(token);
        User user = userService.findByUsername(username);
        if (!audioService.belongsToUser(audioName, user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "音频不存在");
        }

        // 生成 PDF
        Files.createDirectories(storagePaths.pdfDirectory());
        String path = storagePaths.resolvePdf(pdfName).toString();

        PdfWriter writer = new PdfWriter(path);
        PdfDocument pdf = new PdfDocument(writer);
        Document document = new Document(pdf);
        ClassPathResource fontResource = new ClassPathResource("fonts/SanJiHuaChaoTi-Cu-2.ttf");
        PdfFont font = PdfFontFactory.createFont(
                fontResource.getFile().getAbsolutePath(),
                PdfEncodings.IDENTITY_H,
                PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED
        );
        document.setFont(font);
        document.add(new Paragraph("音频文件: " + audioName));
        document.add(new Paragraph("转录文本:"));
        document.add(new Paragraph(transcription));
        document.add(new Paragraph("诊断报告:"));
        document.add(new Paragraph(report));
        document.close();

        // 保存到数据库
        PdfReport pdfReport = new PdfReport();
        pdfReport.setAudioName(audioName);
        pdfReport.setPdfName(pdfName);
        pdfReport.setUserId(user.getId());
        System.out.println(user.getId());
        pdfReportService.save(pdfReport); // ✅ 关键调用 service

        return pdfName;
    }

    @PostMapping("/screening/{audioId}")
    public ResponseEntity<ScreeningTaskResponse> runScreening(
            @PathVariable String audioId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        User user = currentUser(request);
        ScreeningTaskResponse task = screeningTaskService.createForAudioName(
                audioId, user.getId(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/api/v1/screenings/" + task.taskId())
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(task);
    }

    @GetMapping("/file/{name}")
    public void streamAudio(@PathVariable String name,
                            HttpServletRequest request,
                            HttpServletResponse response) throws Exception {
        User user = currentUser(request);
        if (!audioService.belongsToUser(name, user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "音频不存在");
        }

        File file = storagePaths.resolveAudio(name).toFile();
        if (!file.isFile()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "音频文件不存在");
        }
        response.setContentType(AudioMediaTypes.detect(file.toPath()));
        response.setContentLengthLong(file.length());
        response.setHeader("Content-Disposition", "inline; filename=\"" + name + "\"");
        try (FileInputStream input = new FileInputStream(file)) {
            input.transferTo(response.getOutputStream());
        }
    }

    @GetMapping("/pdf/{name}")
    public void downloadPdf(@PathVariable String name,
                            HttpServletResponse response,
                            HttpServletRequest request) throws Exception {

        User user = currentUser(request);
        PdfReport pdfReport = pdfReportService.findByPdfName(name);
        if (pdfReport == null || !pdfReport.getUserId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在");
        }

        File file = storagePaths.resolvePdf(name).toFile();
        if(!file.exists()){
            response.setStatus(404);
            return;
        }
        response.setContentType("application/pdf");
        FileInputStream fis = new FileInputStream(file);
        fis.transferTo(response.getOutputStream());
        fis.close();
    }

    @GetMapping("/pdf/view/{pdfName}")
    public DiagnosisReport viewReport(@PathVariable String pdfName,
                                      HttpServletRequest request) {
        // 通过 PDF 文件名查对应的 audioName
        PdfReport pdfReport = pdfReportService.findByPdfName(pdfName);
        User user = currentUser(request);
        if (pdfReport == null || !pdfReport.getUserId().equals(user.getId())) {
            throw new RuntimeException("PDF不存在");
        }
        // 查询 diagnosis_report 表
        DiagnosisReport report = reportService.findByAudioName(pdfReport.getAudioName());
        if (report == null) {
            throw new RuntimeException("诊断报告不存在");
        }
        return report;
    }

    @GetMapping("/pdf/list")
    public List<PdfReport> listPdf(HttpServletRequest request) {
        String tokenHeader = request.getHeader("Authorization");
        if (tokenHeader == null || !tokenHeader.startsWith("Bearer ")) {
            throw new RuntimeException("未提供有效的 token");
        }
        String token = tokenHeader.substring(7);
        String username = JwtUtil.getUsername(token);
        User user = userService.findByUsername(username);
        return pdfReportService.listByUser(user.getId());
    }

    @GetMapping("/diagnosis/check/{audioName}")
    public Map<String, Object> checkAudio(@PathVariable String audioName,
                                          HttpServletRequest request) {
        User user = currentUser(request);
        if (!audioService.belongsToUser(audioName, user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "音频不存在");
        }
        DiagnosisReport existing = reportService.findByAudioName(audioName);
        Map<String, Object> res = new HashMap<>();
        res.put("alreadyChecked", existing != null);
        return res;
    }

    // 删除历史报告
    @DeleteMapping("/pdf/delete/{pdfName}")
    public Map<String,Object> deleteReport(@PathVariable String pdfName,
                                           HttpServletRequest request) {
        Map<String,Object> res = new HashMap<>();
        try {
            // ⭐ 1. 获取当前用户
            String tokenHeader = request.getHeader("Authorization");
            if (tokenHeader == null || !tokenHeader.startsWith("Bearer ")) {
                throw new RuntimeException("未登录");
            }
            String token = tokenHeader.substring(7);
            String username = JwtUtil.getUsername(token);
            User user = userService.findByUsername(username);
            // ⭐ 2. 找到 pdf 记录
            PdfReport pdfReport = pdfReportService.findByPdfName(pdfName);
            if (pdfReport == null) {
                res.put("success", false);
                res.put("msg", "PDF不存在");
                return res;
            }
            // ⭐ 3. 权限校验
            if (!pdfReport.getUserId().equals(user.getId())) {
                res.put("success", false);
                res.put("msg", "无权限删除该报告");
                return res;
            }
            String audioName = pdfReport.getAudioName();
            // ⭐ 4. 删除服务器文件
            File file = storagePaths.resolvePdf(pdfName).toFile();
            if(file.exists()) {
                file.delete();
            }
            // ⭐ 5. 删除数据库
            pdfReportService.deletepdfByAudio(audioName);
            reportService.deleteByAudio(audioName);

            res.put("success", true);
            res.put("msg", "删除成功");
        } catch (Exception e) {
            e.printStackTrace();
            res.put("success", false);
            res.put("msg", "删除失败");
        }
        return res;
    }

    private User currentUser(HttpServletRequest request) {
        String tokenHeader = request.getHeader("Authorization");
        if (tokenHeader == null || !tokenHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        String username = JwtUtil.getUsername(tokenHeader.substring(7));
        User user = userService.findByUsername(username);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在");
        }
        return user;
    }
}
