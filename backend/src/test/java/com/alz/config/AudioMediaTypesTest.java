package com.alz.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioMediaTypesTest {

    @Test
    void mapsSupportedAudioExtensions() {
        assertEquals("audio/wav", AudioMediaTypes.detect(Path.of("recording.wav")));
        assertEquals("audio/mpeg", AudioMediaTypes.detect(Path.of("recording.mp3")));
        assertEquals("audio/webm", AudioMediaTypes.detect(Path.of("recording.webm")));
        assertEquals("audio/mp4", AudioMediaTypes.detect(Path.of("recording.m4a")));
    }

    @Test
    void fallsBackForUnknownFiles() {
        assertEquals("application/octet-stream", AudioMediaTypes.detect(Path.of("recording.bin")));
    }
}
