package com.alz.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class StoragePaths {

    private final Path audioDirectory;
    private final Path pdfDirectory;
    private final Path adminAudioDirectory;
    private final Path screeningArtifactDirectory;

    @Autowired
    public StoragePaths(
            @Value("${app.storage.audio-dir:data/audio}") String audioDirectory,
            @Value("${app.storage.pdf-dir:data/pdf}") String pdfDirectory,
            @Value("${app.storage.admin-audio-dir:data/admin_audio}") String adminAudioDirectory,
            @Value("${app.storage.screening-artifact-dir:data/screening-artifacts}") String screeningArtifactDirectory) {
        this.audioDirectory = normalizeDirectory(audioDirectory);
        this.pdfDirectory = normalizeDirectory(pdfDirectory);
        this.adminAudioDirectory = normalizeDirectory(adminAudioDirectory);
        this.screeningArtifactDirectory = normalizeDirectory(screeningArtifactDirectory);
    }

    public StoragePaths(String audioDirectory, String pdfDirectory, String adminAudioDirectory) {
        this(audioDirectory, pdfDirectory, adminAudioDirectory, "data/screening-artifacts");
    }

    public Path audioDirectory() {
        return audioDirectory;
    }

    public Path pdfDirectory() {
        return pdfDirectory;
    }

    public Path adminAudioDirectory() {
        return adminAudioDirectory;
    }

    public Path screeningArtifactDirectory() {
        return screeningArtifactDirectory;
    }

    public Path resolveAudio(String filename) {
        return resolveFilename(audioDirectory, filename);
    }

    public Path resolvePdf(String filename) {
        return resolveFilename(pdfDirectory, filename);
    }

    public Path resolveAdminAudio(String filename) {
        return resolveFilename(adminAudioDirectory, filename);
    }

    public Path resolveScreeningArtifact(String taskId, String filename) {
        if (taskId == null || !taskId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("筛查任务 ID 不合法");
        }
        Path taskDirectory = screeningArtifactDirectory.resolve(taskId).normalize();
        if (!taskDirectory.startsWith(screeningArtifactDirectory)) {
            throw new IllegalArgumentException("筛查 artifact 路径不合法");
        }
        return resolveFilename(taskDirectory, filename);
    }

    private Path normalizeDirectory(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("存储目录不能为空");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private Path resolveFilename(Path directory, String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        Path filenamePath = Path.of(filename);
        if (filenamePath.isAbsolute() || filenamePath.getNameCount() != 1
                || !filenamePath.getFileName().toString().equals(filename)) {
            throw new IllegalArgumentException("文件名不合法");
        }
        Path resolved = directory.resolve(filename).normalize();
        if (!resolved.startsWith(directory) || !resolved.getParent().equals(directory)) {
            throw new IllegalArgumentException("文件名不合法");
        }
        return resolved;
    }
}
