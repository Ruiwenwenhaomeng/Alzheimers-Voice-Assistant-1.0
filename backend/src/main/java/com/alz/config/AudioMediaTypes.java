package com.alz.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class AudioMediaTypes {

    private AudioMediaTypes() {
    }

    public static String detect(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".wav") || filename.endsWith(".wave")) return "audio/wav";
        if (filename.endsWith(".mp3")) return "audio/mpeg";
        if (filename.endsWith(".webm")) return "audio/webm";
        if (filename.endsWith(".ogg") || filename.endsWith(".oga")) return "audio/ogg";
        if (filename.endsWith(".m4a") || filename.endsWith(".mp4")) return "audio/mp4";
        if (filename.endsWith(".aac")) return "audio/aac";
        if (filename.endsWith(".flac")) return "audio/flac";

        try {
            String detected = Files.probeContentType(path);
            if (detected != null && detected.toLowerCase(Locale.ROOT).startsWith("audio/")) {
                return detected;
            }
        } catch (IOException ignored) {
            // Unknown formats use a safe binary fallback.
        }
        return "application/octet-stream";
    }
}
