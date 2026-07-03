package com.yang.fileprocessor.entity;

import lombok.Data;

@Data
public class FileDocument {
    private Long id;
    private String fileName;
    private String content;
    private String fileType;
    private String ossUrl;
}