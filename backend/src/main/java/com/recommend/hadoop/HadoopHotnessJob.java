package com.recommend.hadoop;

import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「资料热度」MapReduce 作业 —— 本项目此前只有 HDFS 存储、没有任何计算作业，
 * 这一块是补上的。
 *
 * <p>输入：{@code HadoopDatasetExportService} 导出的 Hive 风格分区 CSV，
 * 每个文件表头为 {@code id,user_id,material_id,action,score,created_at}。</p>
 *
 * <pre>
 * Map     : 每行 → (material_id, 行为权重)
 * Shuffle : 按 material_id 分组
 * Reduce  : 同 material_id 的权重求和
 * Driver  : 汇总结果按权重降序取 TopN
 * </pre>
 *
 * <p><b>跑法</b>：默认用 Hadoop 的 {@link org.apache.hadoop.mapreduce.lib.jobcontrol.JobControl}
 * 本地模式（{@code mapreduce.framework.name=local} + {@code LocalJobRunner}），
 * 不需要搭集群；同一份代码打成 jar 后也能直接 {@code hadoop jar} 提交到真实集群。</p>
 */
@Slf4j
public class HadoopHotnessJob {

    /** 行为权重：与 README 的「资料热度」口径保持一致 */
    public static final Map<String, Double> ACTION_WEIGHTS = buildWeights();

    private static Map<String, Double> buildWeights() {
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("VIEW", 1.0);
        w.put("LIKE", 2.0);
        w.put("COLLECT", 2.5);
        w.put("RATE", 1.5);
        w.put("DOWNLOAD", 2.0);
        w.put("DISLIKE", -1.0);
        return w;
    }

    private static double weightOf(String action) {
        return ACTION_WEIGHTS.getOrDefault(action == null ? "" : action.trim().toUpperCase(), 0.0);
    }

    /**
     * Mapper：解析 CSV 行，输出 (material_id, 权重)。
     * 表头行与非法行直接跳过。
     */
    public static class HotnessMapper extends Mapper<LongWritable, Text, LongWritable, DoubleWritable> {

        private final LongWritable outKey = new LongWritable();
        private final DoubleWritable outValue = new DoubleWritable();

        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {
            String line = value.toString().trim();
            if (line.isEmpty()) {
                return;
            }
            // 跳过表头
            if (line.startsWith("id,") || line.equalsIgnoreCase("id")) {
                return;
            }
            String[] parts = line.split(",", -1);
            if (parts.length < 4) {
                return;
            }
            try {
                long materialId = Long.parseLong(parts[2].trim());
                double weight = weightOf(parts[3]);
                if (weight == 0.0) {
                    return;
                }
                outKey.set(materialId);
                outValue.set(weight);
                context.write(outKey, outValue);
            } catch (NumberFormatException ignored) {
                // 脏数据跳过，不影响整体统计
            }
        }
    }

    /**
     * Reducer：同一 material_id 的权重求和。
     */
    public static class HotnessReducer extends Reducer<LongWritable, DoubleWritable, LongWritable, DoubleWritable> {

        private final DoubleWritable outValue = new DoubleWritable();

        @Override
        protected void reduce(LongWritable key, Iterable<DoubleWritable> values, Context context)
                throws IOException, InterruptedException {
            double sum = 0.0;
            for (DoubleWritable v : values) {
                sum += v.get();
            }
            outValue.set(Math.round(sum * 1000.0) / 1000.0);
            context.write(key, outValue);
        }
    }

    /** 一条热度结果 */
    public record Hotness(long materialId, double score) {
    }

    /** 执行模式 */
    public enum Mode {
        /** 真正的 Hadoop MapReduce（LocalJobRunner 本地模式，或提交到集群） */
        MAPREDUCE,
        /**
         * 进程内近似实现：按 map → 分组 → reduce 的语义用纯 Java 流式跑一遍。
         * 用于 Hadoop 无法启动的场合（例如 Windows 下没有 winutils.exe），
         * 结果与 MapReduce 完全一致，但**耗时不能代表 MapReduce 的真实开销**。
         */
        IN_PROCESS
    }

    /** 一次作业的执行结果 */
    public record JobResult(Mode mode,
                            List<Hotness> topN,
                            int totalMaterials,
                            long elapsedMillis,
                            String note) {
    }

    /**
     * 在本地模式下跑一次作业。
     *
     * @param inputCsvPaths 要统计的 CSV 文件（绝对路径）
     * @param topN          取前多少名
     * @param workDir       临时工作目录（输出会写到这里，调用方负责清理）
     */
    public JobResult runLocal(List<String> inputCsvPaths, int topN, java.nio.file.Path workDir) throws Exception {
        if (inputCsvPaths == null || inputCsvPaths.isEmpty()) {
            throw new IllegalArgumentException("没有可统计的 CSV 文件，请先执行 HDFS 导出");
        }

        java.nio.file.Path outDir = workDir.resolve("hotness-out-" + System.currentTimeMillis());
        java.nio.file.Files.createDirectories(workDir);

        Configuration conf = new Configuration();
        // 本地模式：不需要 YARN/HDFS 集群，直接用 LocalJobRunner
        conf.set("mapreduce.framework.name", "local");
        conf.set("fs.defaultFS", "file:///");
        conf.set("mapreduce.cluster.local.dir",
                workDir.resolve("mr-local").toAbsolutePath().toString());
        // 小数据量下不要为每个切片启动独立 JVM，减少启动开销
        conf.setBoolean("mapreduce.job.jvm.numtasks", false);

        Job job = Job.getInstance(conf, "material-hotness");
        job.setJarByClass(HadoopHotnessJob.class);
        job.setMapperClass(HotnessMapper.class);
        job.setReducerClass(HotnessReducer.class);
        job.setMapOutputKeyClass(LongWritable.class);
        job.setMapOutputValueClass(DoubleWritable.class);
        job.setOutputKeyClass(LongWritable.class);
        job.setOutputValueClass(DoubleWritable.class);
        job.setNumReduceTasks(1);

        for (String p : inputCsvPaths) {
            FileInputFormat.addInputPath(job, new Path(new java.io.File(p).toURI()));
        }
        FileOutputFormat.setOutputPath(job, new Path(outDir.toUri()));

        long start = System.currentTimeMillis();
        boolean ok = job.waitForCompletion(false);
        long elapsed = System.currentTimeMillis() - start;

        if (!ok) {
            throw new IllegalStateException("MapReduce 作业执行失败，请检查日志");
        }

        // 读取 reducer 输出（part-r-00000）
        List<Hotness> all = new ArrayList<>();
        FileSystem fs = FileSystem.getLocal(conf);
        FileStatus[] statuses = fs.listStatus(new Path(outDir.toUri()));
        for (FileStatus st : statuses) {
            if (st.isDirectory() || !st.getPath().getName().startsWith("part-")) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(fs.open(st.getPath()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\t", -1);
                    if (parts.length < 2) {
                        continue;
                    }
                    try {
                        all.add(new Hotness(Long.parseLong(parts[0].trim()),
                                Double.parseDouble(parts[1].trim())));
                    } catch (NumberFormatException ignored) {
                        // 忽略无法解析的行
                    }
                }
            }
        }

        all.sort(Comparator.comparingDouble(Hotness::score).reversed()
                .thenComparingLong(Hotness::materialId));

        List<Hotness> top = all.size() > topN ? all.subList(0, topN) : all;
        log.info("MapReduce 完成: {} 条资料参与统计, 耗时 {} ms, 取 Top{}",
                all.size(), elapsed, top.size());
        return new JobResult(Mode.MAPREDUCE, new ArrayList<>(top), all.size(), elapsed,
                "Hadoop MapReduce 本地模式（LocalJobRunner）");
    }

    /**
     * 优先跑真正的 MapReduce；如果当前环境起不来（典型情况：Windows 上没有
     * winutils.exe，Hadoop 无法设置文件权限），退回到语义等价的进程内实现，
     * 并在返回值里标明实际模式，避免把 fallback 的耗时当成 MapReduce 的性能。
     */
    public JobResult runLocalOrFallback(List<String> inputCsvPaths, int topN,
                                        java.nio.file.Path workDir) {
        try {
            return runLocal(inputCsvPaths, topN, workDir);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("MapReduce 本地模式不可用，改用进程内实现: {}", reason);
            try {
                JobResult fallback = runInProcess(inputCsvPaths, topN);
                return new JobResult(Mode.IN_PROCESS, fallback.topN(), fallback.totalMaterials(),
                        fallback.elapsedMillis(),
                        "MapReduce 启动失败（" + shorten(reason, 160) + "），已退回进程内等价实现；"
                                + "本次耗时反映的是进程内聚合，不代表 MapReduce 的真实开销。");
            } catch (Exception inner) {
                throw new IllegalStateException("MapReduce 与进程内实现均失败: " + inner.getMessage(), inner);
            }
        }
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String one = text.replaceAll("\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    /**
     * 进程内实现的 map → shuffle → reduce：与 {@link HotnessMapper} /
     * {@link HotnessReducer} 用同一套权重与同样的分组语义，只是不走 Hadoop 运行时。
     */
    public JobResult runInProcess(List<String> inputCsvPaths, int topN) throws IOException {
        if (inputCsvPaths == null || inputCsvPaths.isEmpty()) {
            throw new IllegalArgumentException("没有可统计的 CSV 文件，请先执行 HDFS 导出");
        }

        long start = System.currentTimeMillis();

        // map 阶段：逐行解析并输出 (material_id, weight)
        List<LongWritable> keys = new ArrayList<>();
        List<DoubleWritable> values = new ArrayList<>();
        HotnessMapper mapper = new HotnessMapper();
        LongWritable key = new LongWritable();
        long lineNo = 0;

        for (String path : inputCsvPaths) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream(path), StandardCharsets.UTF_8))) {
                String line;
                boolean firstLine = true;
                while ((line = reader.readLine()) != null) {
                    // 复用 Mapper 的解析逻辑需要 Context，这里直接按同样规则解析
                    String trimmed = line.trim();
                    if (firstLine) {
                        firstLine = false;
                        if (trimmed.startsWith("id,") || trimmed.equalsIgnoreCase("id")) {
                            continue;
                        }
                    }
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    String[] parts = trimmed.split(",", -1);
                    if (parts.length < 4) {
                        continue;
                    }
                    try {
                        long materialId = Long.parseLong(parts[2].trim());
                        double weight = weightOf(parts[3]);
                        if (weight == 0.0) {
                            continue;
                        }
                        keys.add(new LongWritable(materialId));
                        values.add(new DoubleWritable(weight));
                        lineNo++;
                    } catch (NumberFormatException ignored) {
                        // 脏数据跳过
                    }
                }
            }
        }

        // shuffle + reduce 阶段：按 material_id 分组求和
        Map<Long, Double> sums = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            sums.merge(keys.get(i).get(), values.get(i).get(), Double::sum);
        }

        List<Hotness> all = new ArrayList<>(sums.size());
        for (Map.Entry<Long, Double> e : sums.entrySet()) {
            all.add(new Hotness(e.getKey(), Math.round(e.getValue() * 1000.0) / 1000.0));
        }
        all.sort(Comparator.comparingDouble(Hotness::score).reversed()
                .thenComparingLong(Hotness::materialId));

        long elapsed = System.currentTimeMillis() - start;
        List<Hotness> top = all.size() > topN ? all.subList(0, topN) : all;
        log.info("进程内聚合完成: 输入 {} 行, {} 条资料, 耗时 {} ms",
                lineNo, all.size(), elapsed);
        return new JobResult(Mode.IN_PROCESS, new ArrayList<>(top), all.size(), elapsed,
                "进程内 map→reduce（未走 Hadoop 运行时）");
    }

    /**
     * 与 Mapper 完全一致的权重口径，供 SQL 侧拼 CASE WHEN 使用，
     * 保证两条链路的对比是同一个定义。
     */
    public static String sqlWeightCase(String column) {
        StringBuilder sb = new StringBuilder("SUM(CASE UPPER(").append(column).append(")");
        for (Map.Entry<String, Double> e : ACTION_WEIGHTS.entrySet()) {
            sb.append(" WHEN '").append(e.getKey()).append("' THEN ").append(e.getValue());
        }
        return sb.append(" ELSE 0 END)").toString();
    }
}
