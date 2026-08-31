package com.alz.validation;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Component
public class AudioUploadValidator {

    static final long MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;
    static final double MIN_DURATION_SECONDS = 1.0;
    static final double MAX_DURATION_SECONDS = 300.0;

    public int validateAndMeasureDuration(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("音频文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("音频文件不能超过 25 MB");
        }

        verifyWaveHeader(file);
        try (InputStream raw = file.getInputStream();
             BufferedInputStream buffered = new BufferedInputStream(raw);
             AudioInputStream audio = AudioSystem.getAudioInputStream(buffered)) {
            AudioFormat format = audio.getFormat();
            validatePcmFormat(format);

            long frameLength = audio.getFrameLength();
            if (frameLength <= 0 || format.getFrameRate() <= 0) {
                throw new IllegalArgumentException("无法读取 WAV 音频时长");
            }
            double durationSeconds = frameLength / format.getFrameRate();
            if (durationSeconds < MIN_DURATION_SECONDS) {
                throw new IllegalArgumentException("录音至少需要 1 秒");
            }
            if (durationSeconds > MAX_DURATION_SECONDS) {
                throw new IllegalArgumentException("录音不能超过 5 分钟");
            }
            return Math.max(1, (int) Math.round(durationSeconds));
        } catch (UnsupportedAudioFileException | IOException exception) {
            throw new IllegalArgumentException("文件不是可解析的 PCM WAV 音频", exception);
        }
    }

    private void verifyWaveHeader(MultipartFile file) {
        byte[] header = new byte[12];
        try (InputStream input = file.getInputStream()) {
            int read = input.readNBytes(header, 0, header.length);
            if (read != header.length
                    || !Arrays.equals(Arrays.copyOfRange(header, 0, 4), "RIFF".getBytes(StandardCharsets.US_ASCII))
                    || !Arrays.equals(Arrays.copyOfRange(header, 8, 12), "WAVE".getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("仅支持 RIFF/WAVE 格式的音频");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取上传的音频文件", exception);
        }
    }

    private void validatePcmFormat(AudioFormat format) {
        AudioFormat.Encoding encoding = format.getEncoding();
        if (!AudioFormat.Encoding.PCM_SIGNED.equals(encoding)
                && !AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding)) {
            throw new IllegalArgumentException("仅支持未压缩 PCM WAV 音频");
        }
        float sampleRate = format.getSampleRate();
        int channels = format.getChannels();
        int sampleSize = format.getSampleSizeInBits();
        if (sampleRate < 8_000 || sampleRate > 48_000) {
            throw new IllegalArgumentException("采样率需在 8 kHz 到 48 kHz 之间");
        }
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("仅支持单声道或双声道音频");
        }
        if (sampleSize != 8 && sampleSize != 16 && sampleSize != 24 && sampleSize != 32) {
            throw new IllegalArgumentException("不支持该 PCM 位深");
        }
    }
}
