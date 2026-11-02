package com.recommend.hadoop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HadoopHotnessJobTest {

    @TempDir
    Path tempDir;

    @Test
    void inProcessAggregatesWeightsAndSortsTopN() throws Exception {
        Path csv = tempDir.resolve("user_behaviors.csv");
        Files.writeString(csv, String.join("\n",
                "id,user_id,material_id,action,score,created_at",
                "1,1,10,VIEW,1,2026-01-01 00:00:00",
                "2,1,10,LIKE,2,2026-01-01 00:00:00",
                "3,2,11,COLLECT,2.5,2026-01-01 00:00:00",
                "4,2,12,COMMENT,0,2026-01-01 00:00:00"
        ), StandardCharsets.UTF_8);

        HadoopHotnessJob.JobResult result = new HadoopHotnessJob()
                .runInProcess(List.of(csv.toString()), 2);

        assertThat(result.topN()).extracting(HadoopHotnessJob.Hotness::materialId)
                .containsExactly(10L, 11L);
        assertThat(result.topN().get(0).score()).isEqualTo(3.0);
        assertThat(result.totalMaterials()).isEqualTo(2);
    }

    @Test
    void clusterModeRejectsBlankInputBeforeTouchingHdfs() {
        assertThatThrownBy(() -> new HadoopHotnessJob()
                .runCluster(new org.apache.hadoop.conf.Configuration(), " ", "/tmp/out", 10, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HDFS 输入路径不能为空");
    }

    @Test
    void sqlWeightCaseMatchesActionWeights() {
        String expression = HadoopHotnessJob.sqlWeightCase("action");

        assertThat(expression).contains("WHEN 'VIEW' THEN 1.0");
        assertThat(expression).contains("WHEN 'COLLECT' THEN 2.5");
        assertThat(expression).endsWith("ELSE 0 END)");
    }
}
