package com.yang.fileprocessor.dto;

import lombok.Data;

/**
 * 文件上传消息
 * Controller 发送这个消息到 RabbitMQ 队列
 * Consumer 从队列取出后，根据消息内容上传文件到 OSS
 */

@Data
public class FileUploadMessage {
    private Long fileId;       // 文件记录的 ID
    private String filePath;   // 文件临时保存路径
    private String fileName;   // 原始文件名
}