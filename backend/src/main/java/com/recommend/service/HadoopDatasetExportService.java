package com.recommend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HadoopDatasetExportService {

    private final JdbcTemplate jdbcTemplate;
    private final FileSystem fileSystem;

    @Value("${hadoop.export.enabled:true}")
    private boolean exportEnabled;

    @Value("${hadoop.export.base-path:/recommend/warehouse}")
    private String hdfsExportBasePath;

    @Value("${hadoop.export.local-root:./storage/hadoop-export}")
    private String localExportRoot;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Scheduled(cron = "${hadoop.export.cron:0 30 2 * * ?}")
    public void scheduledExport() {
        if (!exportEnabled) {
            log.debug("Hadoop dataset export disabled by config.");
            return;
        }
        try {
            Map<String, Object> summary = exportNow("scheduled");
            log.info("Scheduled Hadoop export finished: {}", summary);
        } catch (Exception e) {
            log.error("Scheduled Hadoop export failed: {}", e.getMessage(), e);
        }
    }

    public Map<String, Object> exportNow(String trigger) throws Exception {
        String date = LocalDate.now().format(DATE_FMT);
        String ts = LocalDateTime.now().format(TS_FMT);

        try {
            List<Map<String, Object>> exports = new ArrayList<>();
            exports.add(exportTableToCsv(
                    "user_behaviors",
                    "SELECT id, user_id, material_id, action, score, created_at FROM user_behaviors ORDER BY id",
                    List.of("id", "user_id", "material_id", "action", "score", "created_at"),
                    date,
                    ts
            ));
            exports.add(exportTableToCsv(
                    "user_ratings",
                    "SELECT id, user_id, material_id, rating, created_at FROM user_ratings ORDER BY id",
                    List.of("id", "user_id", "material_id", "rating", "created_at"),
                    date,
                    ts
            ));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("trigger", trigger);
            result.put("generatedAt", LocalDateTime.now().toString());
            result.put("storageScheme", fileSystem.getScheme());
            result.put("storageUri", fileSystem.getUri().toString());
            result.put("fallback", false);
            result.put("exports", exports);
            return result;
        } catch (Exception e) {
            if (isLocalFileSystem()) {
                throw e;
            }
            log.warn("HDFS export failed, falling back to local export: {}", e.getMessage());
            return exportNowToLocalFallback(trigger, date, ts, e);
        }
    }

    private Map<String, Object> exportNowToLocalFallback(String trigger, String date, String ts, Exception cause) throws Exception {
        java.nio.file.Path localBase = Paths.get(localExportRoot).toAbsolutePath().normalize();
        Files.createDirectories(localBase);

        List<Map<String, Object>> exports = new ArrayList<>();
        exports.add(exportTableToLocalCsv(
                localBase,
                "user_behaviors",
                "SELECT id, user_id, material_id, action, score, created_at FROM user_behaviors ORDER BY id",
                List.of("id", "user_id", "material_id", "action", "score", "created_at"),
                date,
                ts
        ));
        exports.add(exportTableToLocalCsv(
                localBase,
                "user_ratings",
                "SELECT id, user_id, material_id, rating, created_at FROM user_ratings ORDER BY id",
                List.of("id", "user_id", "material_id", "rating", "created_at"),
                date,
                ts
        ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trigger", trigger);
        result.put("generatedAt", LocalDateTime.now().toString());
        result.put("storageScheme", "file-fallback");
        result.put("storageUri", localBase.toUri().toString());
        result.put("fallback", true);
        result.put("fallbackReason", cause.getMessage());
        result.put("exports", exports);
        return result;
    }

    public Map<String, Object> getLatestExportSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", LocalDateTime.now().toString());
        result.put("storageScheme", fileSystem.getScheme());
        result.put("storageUri", fileSystem.getUri().toString());
        try {
            Path baseDir = physicalBaseDir();
            result.put("basePath", baseDir.toString());
            result.put("exists", fileSystem.exists(baseDir));

            List<Map<String, Object>> datasets = new ArrayList<>();
            long totalRows = 0L;
            if (fileSystem.exists(baseDir)) {
                datasets.add(readLatestDatasetExport(baseDir, "user_behaviors"));
                datasets.add(readLatestDatasetExport(baseDir, "user_ratings"));
                for (Map<String, Object> item : datasets) {
                    Object rows = item.get("rows");
                    if (rows instanceof Number number) {
                        totalRows += number.longValue();
                    }
                }
            }
            result.put("datasets", datasets);
            result.put("totalRows", totalRows);
            result.put("available", true);
            return result;
        } catch (Exception e) {
            log.warn("Failed to query latest Hadoop export summary: {}", e.getMessage());
            return getLatestLocalFallbackSummary(result, e);
        }
    }

    private Map<String, Object> exportTableToCsv(String dataset,
                                                 String sql,
                                                 List<String> columns,
                                                 String date,
                                                 String ts) throws Exception {
        Path baseDir = physicalBaseDir();
        Path datasetDir = new Path(new Path(baseDir, dataset), "dt=" + date);
        Path outputPath = new Path(datasetDir, dataset + "-" + ts + ".csv");

        fileSystem.mkdirs(datasetDir);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        try (FSDataOutputStream output = fileSystem.create(outputPath, true);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            writer.write(String.join(",", columns));
            writer.newLine();

            for (Map<String, Object> row : rows) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        line.append(',');
                    }
                    line.append(toCsvValue(valueOf(row, columns.get(i))));
                }
                writer.write(line.toString());
                writer.newLine();
            }
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dataset", dataset);
        item.put("rows", rows.size());
        item.put("path", outputPath.toString());
        return item;
    }

    private Map<String, Object> exportTableToLocalCsv(java.nio.file.Path localBaseDir,
                                                      String dataset,
                                                      String sql,
                                                      List<String> columns,
                                                      String date,
                                                      String ts) throws Exception {
        java.nio.file.Path datasetDir = localBaseDir.resolve(dataset).resolve("dt=" + date);
        Files.createDirectories(datasetDir);
        java.nio.file.Path outputPath = datasetDir.resolve(dataset + "-" + ts + ".csv");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", columns));
            writer.newLine();

            for (Map<String, Object> row : rows) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        line.append(',');
                    }
                    line.append(toCsvValue(valueOf(row, columns.get(i))));
                }
                writer.write(line.toString());
                writer.newLine();
            }
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dataset", dataset);
        item.put("rows", rows.size());
        item.put("path", outputPath.toString());
        return item;
    }

    private Map<String, Object> readLatestDatasetExport(Path baseDir, String dataset) throws Exception {
        Path datasetDir = new Path(baseDir, dataset);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataset", dataset);
        result.put("basePath", datasetDir.toString());

        if (!fileSystem.exists(datasetDir)) {
            result.put("exists", false);
            return result;
        }

        List<FileStatus> candidates = new ArrayList<>();
        for (FileStatus partStatus : fileSystem.listStatus(datasetDir)) {
            if (!partStatus.isDirectory()) {
                continue;
            }
            for (FileStatus itemStatus : fileSystem.listStatus(partStatus.getPath())) {
                if (!itemStatus.isFile()) {
                    continue;
                }
                String name = itemStatus.getPath().getName().toLowerCase();
                if (!name.endsWith(".csv")) {
                    continue;
                }
                candidates.add(itemStatus);
            }
        }

        result.put("exists", true);
        result.put("fileCount", candidates.size());
        if (candidates.isEmpty()) {
            return result;
        }

        FileStatus latest = candidates.stream()
                .max(Comparator.comparingLong(FileStatus::getModificationTime))
                .orElse(candidates.get(0));

        long rowCount = countCsvRows(latest.getPath());
        result.put("latestFile", latest.getPath().toString());
        result.put("latestModifiedAt", LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(latest.getModificationTime()),
                ZoneId.systemDefault()).toString());
        result.put("latestFileSizeBytes", latest.getLen());
        result.put("rows", Math.max(rowCount - 1, 0));
        return result;
    }

    private long countCsvRows(Path csvPath) {
        long lines = 0L;
        try (var input = fileSystem.open(csvPath);
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                lines++;
            }
            return lines;
        } catch (Exception e) {
            log.warn("Failed to count rows for {}: {}", csvPath, e.getMessage());
            return 0L;
        }
    }

    private Map<String, Object> getLatestLocalFallbackSummary(Map<String, Object> result, Exception cause) {
        java.nio.file.Path localBase = Paths.get(localExportRoot).toAbsolutePath().normalize();
        result.put("storageScheme", "file-fallback");
        result.put("storageUri", localBase.toUri().toString());
        result.put("basePath", localBase.toString());
        result.put("fallback", true);
        result.put("fallbackReason", cause.getMessage());

        List<Map<String, Object>> datasets = new ArrayList<>();
        long totalRows = 0L;

        if (Files.exists(localBase)) {
            datasets.add(readLatestLocalDatasetExport(localBase, "user_behaviors"));
            datasets.add(readLatestLocalDatasetExport(localBase, "user_ratings"));
            for (Map<String, Object> item : datasets) {
                Object rows = item.get("rows");
                if (rows instanceof Number number) {
                    totalRows += number.longValue();
                }
            }
        }

        result.put("exists", Files.exists(localBase));
        result.put("datasets", datasets);
        result.put("totalRows", totalRows);
        result.put("available", Files.exists(localBase));
        result.put("error", cause.getMessage());
        return result;
    }

    private Map<String, Object> readLatestLocalDatasetExport(java.nio.file.Path localBase, String dataset) {
        java.nio.file.Path datasetDir = localBase.resolve(dataset);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataset", dataset);
        result.put("basePath", datasetDir.toString());

        if (!Files.exists(datasetDir)) {
            result.put("exists", false);
            return result;
        }

        PathMatcher matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:**/*.csv");
        List<java.nio.file.Path> files = new ArrayList<>();
        try (var stream = Files.walk(datasetDir, 3)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> matcher.matches(path))
                    .forEach(files::add);
        } catch (Exception e) {
            result.put("exists", true);
            result.put("error", e.getMessage());
            return result;
        }

        result.put("exists", true);
        result.put("fileCount", files.size());
        if (files.isEmpty()) {
            return result;
        }

        java.nio.file.Path latest = files.stream()
                .max(Comparator.comparingLong(this::lastModifiedMillis))
                .orElse(files.get(0));

        result.put("latestFile", latest.toString());
        result.put("latestModifiedAt", LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(lastModifiedMillis(latest)),
                ZoneId.systemDefault()).toString());
        result.put("latestFileSizeBytes", fileSize(latest));
        result.put("rows", Math.max(countLocalCsvRows(latest) - 1, 0));
        return result;
    }

    private long countLocalCsvRows(java.nio.file.Path path) {
        try (var stream = Files.lines(path, StandardCharsets.UTF_8)) {
            return stream.count();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long lastModifiedMillis(java.nio.file.Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long fileSize(java.nio.file.Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    private Path physicalBaseDir() {
        if (isLocalFileSystem()) {
            java.nio.file.Path localPath = Paths.get(localExportRoot).toAbsolutePath().normalize();
            return new Path(localPath.toUri());
        }
        return new Path(normalizeHdfsPath(hdfsExportBasePath));
    }

    private boolean isLocalFileSystem() {
        String scheme = fileSystem.getScheme();
        return scheme == null || "file".equalsIgnoreCase(scheme);
    }

    private String normalizeHdfsPath(String path) {
        String normalized = (path == null || path.isBlank()) ? "/recommend/warehouse" : path.trim().replace("\\", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private Object valueOf(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value != null) {
            return value;
        }
        value = row.get(column.toUpperCase());
        if (value != null) {
            return value;
        }
        return row.get(column.toLowerCase());
    }

    private String toCsvValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        boolean needQuote = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r");
        if (!needQuote) {
            return text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
