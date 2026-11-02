package com.recommend.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;

@Slf4j
@Service
public class HdfsService {

    private final FileSystem fileSystem;

    public HdfsService(FileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    @Value("${storage.hdfs-base-path:/recommend/materials}")
    private String hdfsBasePath;

    @Value("${storage.local-root:./storage}")
    private String localRoot;

    public String uploadFile(Long materialId, String filename, InputStream inputStream) throws IOException {
        String safeFilename = Paths.get(filename == null ? "file.bin" : filename).getFileName().toString();
        String relativePath = materialId + "/" + safeFilename;

        Path targetPath = new Path(physicalBaseDir(), relativePath);
        Path parent = targetPath.getParent();
        if (parent != null && !fileSystem.exists(parent)) {
            fileSystem.mkdirs(parent);
        }

        try (FSDataOutputStream output = fileSystem.create(targetPath, true)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, len);
            }
        }

        String logicalPath = logicalBasePath() + "/" + relativePath;
        log.info("Uploaded file to storage: logicalPath={}, physicalPath={}, fs={}", logicalPath, targetPath, fileSystem.getScheme());
        return logicalPath;
    }

    public InputStream downloadFile(String hdfsPath) throws IOException {
        Path path = resolvePath(hdfsPath);
        if (!fileSystem.exists(path)) {
            throw new IllegalArgumentException("File not found on HDFS: " + hdfsPath);
        }
        return fileSystem.open(path);
    }

    public void deleteFile(String hdfsPath) throws IOException {
        Path path = resolvePath(hdfsPath);
        if (fileSystem.exists(path)) {
            fileSystem.delete(path, false);
            log.info("Deleted file from storage: logicalPath={}, physicalPath={}", hdfsPath, path);
        }
    }

    public boolean exists(String hdfsPath) throws IOException {
        return fileSystem.exists(resolvePath(hdfsPath));
    }

    private Path resolvePath(String hdfsPath) throws IOException {
        if (hdfsPath == null || hdfsPath.isBlank()) {
            throw new IllegalArgumentException("Invalid file path");
        }

        String relative = toRelativePath(hdfsPath);
        return new Path(physicalBaseDir(), relative);
    }

    private Path physicalBaseDir() {
        if (isLocalFileSystem()) {
            java.nio.file.Path localPath = Paths.get(localRoot, "recommend", "materials")
                    .toAbsolutePath()
                    .normalize();
            return new Path(localPath.toUri());
        }
        return new Path(logicalBasePath());
    }

    private String toRelativePath(String hdfsPath) {
        String normalized = hdfsPath.replace("\\", "/").trim();
        String base = logicalBasePath();

        String relative;
        if (normalized.equals(base)) {
            throw new IllegalArgumentException("Invalid file path");
        } else if (normalized.startsWith(base + "/")) {
            relative = normalized.substring(base.length() + 1);
        } else {
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            relative = normalized;
        }

        java.nio.file.Path normalizedRel = Paths.get(relative).normalize();
        if (normalizedRel.isAbsolute() || normalizedRel.startsWith("..")) {
            throw new IllegalArgumentException("Illegal file path");
        }

        String rel = normalizedRel.toString().replace("\\", "/");
        if (rel.isBlank() || rel.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Illegal file path");
        }
        return rel;
    }

    private String logicalBasePath() {
        String base = (hdfsBasePath == null || hdfsBasePath.isBlank()) ? "/recommend/materials" : hdfsBasePath.trim();
        base = base.replace("\\", "/");
        if (!base.startsWith("/")) {
            base = "/" + base;
        }
        while (base.endsWith("/") && base.length() > 1) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private boolean isLocalFileSystem() {
        String scheme = fileSystem.getScheme();
        return scheme == null || "file".equalsIgnoreCase(scheme);
    }
}
