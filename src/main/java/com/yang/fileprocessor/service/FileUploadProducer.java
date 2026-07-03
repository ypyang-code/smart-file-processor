package com.yang.fileprocessor.service;

import com.yang.fileprocessor.config.RabbitMqConfig;
import com.yang.fileprocessor.dto.FileUploadMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 消息生产者
 * 把文件上传任务发送到 RabbitMQ 队列
 */
@Service
public class FileUploadProducer {

    private static final Logger log = LoggerFactory.getLogger(FileUploadProducer.class);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void sendUploadTask(FileUploadMessage message) {
        log.info("发送文件上传任务到队列：fileId={}, fileName={}", message.getFileId(), message.getFileName());
        rabbitTemplate.convertAndSend(RabbitMqConfig.FILE_UPLOAD_QUEUE, message);
    }
}