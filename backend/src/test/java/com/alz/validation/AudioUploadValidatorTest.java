package com.alz.validation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioUploadValidatorTest {

    private final AudioUploadValidator validator = new AudioUploadValidator();

    @Test
    void acceptsPcmWaveAndUsesMeasuredDuration() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.wav", "audio/wav", pcmWave(16_000, 2.0));

        assertEquals(2, validator.validateAndMeasureDuration(file));
    }

    @Test
    void rejectsAFileThatOnlyUsesTheWavExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.wav", "audio/wav", "not audio".getBytes());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateAndMeasureDuration(file));
        assertTrue(exception.getMessage().contains("RIFF/WAVE"));
    }

    @Test
    void rejectsAudioShorterThanOneSecond() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "short.wav", "audio/wav", pcmWave(16_000, 0.5));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateAndMeasureDuration(file));
        assertTrue(exception.getMessage().contains("至少需要 1 秒"));
    }

    private static byte[] pcmWave(int sampleRate, double durationSeconds) {
        int sampleCount = (int) (sampleRate * durationSeconds);
        int dataSize = sampleCount * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'});
        buffer.putInt(36 + dataSize);
        buffer.put(new byte[]{'W', 'A', 'V', 'E'});
        buffer.put(new byte[]{'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put(new byte[]{'d', 'a', 't', 'a'});
        buffer.putInt(dataSize);
        while (buffer.hasRemaining()) {
            buffer.putShort((short) 0);
        }
        return buffer.array();
    }
}
