package com.yang.fileprocessor.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class FileInfo {
    private Long id;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String ossUrl;
    private String content;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}