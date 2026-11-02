package com.recommend.config;

import org.apache.hadoop.fs.FileSystem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Configuration
public class HadoopConfig {

    @Value("${hadoop.uri}")
    private String hdfsUri;

    @Value("${hadoop.user}")
    private String hdfsUser;

    @Value("${hadoop.job-jar:}")
    private String jobJar;

    @Bean
    public org.apache.hadoop.conf.Configuration hadoopConfiguration() {
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        String confDir = System.getenv("HADOOP_CONF_DIR");
        if (confDir != null && !confDir.isBlank()) {
            for (String name : List.of("core-site.xml", "hdfs-site.xml", "yarn-site.xml", "mapred-site.xml")) {
                Path path = Path.of(confDir, name);
                if (Files.isRegularFile(path)) {
                    conf.addResource(new org.apache.hadoop.fs.Path(path.toUri()));
                }
            }
        }
        if (jobJar != null && !jobJar.isBlank()) {
            conf.set("hadoop.job.jar", jobJar);
        }
        conf.set("fs.defaultFS", hdfsUri);
        conf.set("fs.hdfs.impl", org.apache.hadoop.hdfs.DistributedFileSystem.class.getName());
        conf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());
        return conf;
    }

    @Bean
    @Lazy
    public FileSystem hdfsFileSystem(org.apache.hadoop.conf.Configuration hadoopConfiguration) throws IOException {
        try {
            return FileSystem.get(URI.create(hdfsUri), hadoopConfiguration, hdfsUser);
        } catch (Exception e) {
            // If HDFS is not available, fall back to local filesystem for development
            return FileSystem.getLocal(hadoopConfiguration);
        }
    }
}
