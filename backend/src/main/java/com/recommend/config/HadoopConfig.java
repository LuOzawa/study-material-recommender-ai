package com.recommend.config;

import org.apache.hadoop.fs.FileSystem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.IOException;
import java.net.URI;

@Configuration
public class HadoopConfig {

    @Value("${hadoop.uri}")
    private String hdfsUri;

    @Value("${hadoop.user}")
    private String hdfsUser;

    @Bean
    public org.apache.hadoop.conf.Configuration hadoopConfiguration() {
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
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
