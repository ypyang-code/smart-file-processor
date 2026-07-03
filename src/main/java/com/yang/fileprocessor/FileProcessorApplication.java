package com.yang.fileprocessor;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan("com.yang.fileprocessor.mapper")
@EnableConfigurationProperties
public class FileProcessorApplication {
    public static void main(String[] args) {
        SpringApplication.run(FileProcessorApplication.class, args);
    }
}