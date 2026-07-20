package com.yang.fileprocessor;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.yang.fileprocessor.mapper")
@EnableConfigurationProperties
@EnableScheduling
public class FileProcessorApplication {
    public static void main(String[] args) {
        SpringApplication.run(FileProcessorApplication.class, args);
    }
}