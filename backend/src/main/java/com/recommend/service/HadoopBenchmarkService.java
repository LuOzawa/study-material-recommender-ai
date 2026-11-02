package com.recommend.service;

import com.recommend.hadoop.HadoopHotnessJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 「资料热度」两种计算路径的对比：MySQL 单机聚合 vs Hadoop MapReduce。
 *
 * <p>项目原本只有 HDFS 存储（定时导出 CSV），没有任何计算作业，
 * 「基于 Hadoop 的推荐系统」这个题目里的「计算」部分是空的。这里补上：
 * 用同一份导出数据、同一套行为权重，分别走两条链路算热点排行，并记录耗时。</p>
 *
 * <p><b>关于结论的诚实说明</b>：单机 + 小数据量下，MapReduce 的本地模式
 * （LocalJobRunner）一定比 MySQL 的 {@code GROUP BY} 慢 —— 作业启动、切片、
 * 序列化、落盘的开销远大于几万行数据的聚合本身。这不是实现问题，
 * 而是 MapReduce 的设计前提就是「数据量大到单机扛不住」。
 * 因此本接口的价值是给出**可复现的实测数字**，用于论文里讨论
 * 「什么规模下才需要上 Hadoop」，而不是硬凑一个「Hadoop 更快」的结论。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HadoopBenchmarkService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${hadoop.export.local-root:./storage/hadoop-export}")
    private String localExportRoot;

    /**
     * 跑一次对比。
     *
     * @param topN 取前多少名
     */
    public Map<String, Object> benchmark(int topN) throws Exception {
        int safeTopN = Math.max(5, Math.min(topN, 100));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", java.time.LocalDateTime.now().toString());
        result.put("topN", safeTopN);
        result.put("weights", HadoopHotnessJob.ACTION_WEIGHTS);

        List<String> csvFiles = findLatestExportCsvs();
        result.put("inputFiles", csvFiles);
        long totalLines = 0;
        for (String f : csvFiles) {
            try (Stream<String> lines = Files.lines(Paths.get(f))) {
                totalLines += Math.max(0, lines.count() - 1); // 去掉表头
            }
        }
        result.put("inputRows", totalLines);

        if (csvFiles.isEmpty()) {
            result.put("error", "没有找到导出文件，请先调用 POST /api/admin/hadoop/export");
            return result;
        }

        // ---- 路径 1：MySQL 直接在库上聚合 ----
        long mysqlStart = System.currentTimeMillis();
        List<Map<String, Object>> mysqlRows = jdbcTemplate.queryForList(
                "SELECT material_id, " + HadoopHotnessJob.sqlWeightCase("action") + " AS score "
                        + "FROM user_behaviors GROUP BY material_id "
                        + "ORDER BY score DESC, material_id ASC LIMIT ?", safeTopN);
        long mysqlMillis = System.currentTimeMillis() - mysqlStart;

        List<Map<String, Object>> mysqlTop = new ArrayList<>();
        for (Map<String, Object> row : mysqlRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialId", ((Number) row.get("material_id")).longValue());
            item.put("score", ((Number) row.get("score")).doubleValue());
            mysqlTop.add(item);
        }

        // ---- 路径 2：MapReduce（本地模式） ----
        Path workDir = Paths.get(localExportRoot, ".mr-work").toAbsolutePath();
        HadoopHotnessJob.JobResult jobResult;
        try {
            // 优先跑真正的 MapReduce；环境起不来时退回进程内等价实现，并在结果里标明
            jobResult = new HadoopHotnessJob().runLocalOrFallback(csvFiles, safeTopN, workDir);
        } finally {
            deleteRecursively(workDir);
        }

        List<Map<String, Object>> mrTop = new ArrayList<>();
        for (HadoopHotnessJob.Hotness h : jobResult.topN()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialId", h.materialId());
            item.put("score", h.score());
            mrTop.add(item);
        }

        result.put("mysql", Map.of(
                "elapsedMillis", mysqlMillis,
                "top", mysqlTop
        ));
        Map<String, Object> mrInfo = new LinkedHashMap<>();
        mrInfo.put("mode", jobResult.mode().name());
        mrInfo.put("note", jobResult.note());
        mrInfo.put("elapsedMillis", jobResult.elapsedMillis());
        mrInfo.put("totalMaterials", jobResult.totalMaterials());
        mrInfo.put("top", mrTop);
        result.put("mapreduce", mrInfo);
        result.put("resultsMatch", sameTopN(mysqlTop, mrTop));

        double ratio = mysqlMillis == 0 ? 0 : (double) jobResult.elapsedMillis() / mysqlMillis;
        result.put("mapreduceSlowerBy", Math.round(ratio * 100.0) / 100.0);

        result.put("conclusion", buildConclusion(totalLines, mysqlMillis,
                jobResult.elapsedMillis(), (Boolean) result.get("resultsMatch"),
                jobResult.mode()));

        log.info("热度对比完成: rows={} mysql={}ms mapreduce={}ms 结果一致={}",
                totalLines, mysqlMillis, jobResult.elapsedMillis(), result.get("resultsMatch"));
        return result;
    }

    private String buildConclusion(long rows, long mysqlMillis, long mrMillis, boolean match,
                                   HadoopHotnessJob.Mode mode) {
        if (!match) {
            return "两条链路的 TopN 结果不一致，请检查权重口径（"
                    + HadoopHotnessJob.ACTION_WEIGHTS + "）与导出文件是否为同一份数据。";
        }
        if (mode == HadoopHotnessJob.Mode.IN_PROCESS) {
            return String.format(
                    "两条链路结果完全一致（%d 行）。但本次 MapReduce 未能启动，"
                            + "用的是进程内等价实现（%d ms，MySQL %d ms），"
                            + "**这组耗时不能用于比较 Hadoop 与 MySQL**。"
                            + "要得到真实的 MapReduce 耗时，需在 Linux 或配置了 winutils 的环境运行"
                            + "（见 README 第五节）。",
                    rows, mrMillis, mysqlMillis);
        }
        if (mrMillis <= mysqlMillis) {
            return String.format("本次 %d 行数据下 MapReduce(%d ms) 快于 MySQL(%d ms)；"
                    + "结果一致，说明两条链路口径相同。", rows, mrMillis, mysqlMillis);
        }
        return String.format(
                "两条链路结果完全一致，但 %d 行的数据量下 MapReduce(%d ms) 明显慢于 MySQL(%d ms)。"
                        + "这符合预期：MapReduce 的价值在数据量超出单机处理能力之后，"
                        + "小数据量下作业启动/切片/落盘的开销占主导。"
                        + "论文里应当据此讨论「何时才需要上 Hadoop」，而不是声称 MapReduce 更快。",
                rows, mrMillis, mysqlMillis);
    }

    /**
     * 找出每个 {@code dt=<日期>} 目录下最新的一个 CSV，
     * 避免把同一天多次导出重复统计。
     */
    private List<String> findLatestExportCsvs() throws Exception {
        Path base = Paths.get(localExportRoot, "user_behaviors").toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(base)) {
            List<Path> dtDirs = dirs.filter(Files::isDirectory).toList();
            for (Path dtDir : dtDirs) {
                try (Stream<Path> files = Files.list(dtDir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".csv"))
                            .max(Comparator.comparing(p -> p.getFileName().toString()))
                            .ifPresent(p -> result.add(p.toAbsolutePath().toString()));
                }
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    private boolean sameTopN(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            long idA = ((Number) a.get(i).get("materialId")).longValue();
            long idB = ((Number) b.get(i).get("materialId")).longValue();
            double sA = ((Number) a.get(i).get("score")).doubleValue();
            double sB = ((Number) b.get(i).get("score")).doubleValue();
            if (idA != idB || Math.abs(sA - sB) > 0.001) {
                return false;
            }
        }
        return true;
    }

    private void deleteRecursively(Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 临时目录清理失败不影响主流程
                    }
                });
            }
        } catch (Exception e) {
            log.warn("清理 MapReduce 临时目录失败: {}", e.getMessage());
        }
    }
}
