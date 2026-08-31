package com.alz.service;

import com.alz.entity.AudioDiagnosis;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

public interface PythonService {
    /**
     * 获取音频的转录文本与诊断报告
     * @param audioFilePath 音频文件路径
     * @return 包含转录文本和诊断报告的对象
     * @throws IOException 文件或流处理异常
     * @throws InterruptedException 进程等待中断异常
     */
    AudioDiagnosis getDiagnosisReport(String audioFilePath) throws IOException, InterruptedException;
}