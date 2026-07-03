package com.yang.fileprocessor.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.yang.fileprocessor.config.RabbitMqConfig;
import com.yang.fileprocessor.dto.FileUploadMessage;
import com.yang.fileprocessor.entity.FileDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.InputStream;

@Service
public class FileUploadConsumer {

    private static final Logger log = LoggerFactory.getLogger(FileUploadConsumer.class);

    @Autowired
    private OssService ossService;

    @Autowired
    private FileInfoService fileInfoService;

    @Autowired
    private FileContentExtractor contentExtractor;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @RabbitListener(queues = RabbitMqConfig.FILE_UPLOAD_QUEUE)
    public void handleFileUpload(FileUploadMessage message) {
        log.info("开始处理文件上传：fileId={}, fileName={}", message.getFileId(), message.getFileName());

        try {
            // 1. 上传到 OSS
            InputStream inputStream = new FileInputStream(message.getFilePath());
            String ossFileName = "files/" + message.getFileId() + "_" + message.getFileName();
            String ossUrl = ossService.uploadFile(ossFileName, inputStream);
            inputStream.close();
            log.info("OSS上传完成：fileId={}, ossUrl={}", message.getFileId(), ossUrl);

            // 2. 提取文件文字内容
            String fileType = getFileType(message.getFileName());
            String content = contentExtractor.extractContent(message.getFilePath(), fileType);
            log.info("内容提取完成：fileId={}, 内容长度={}", message.getFileId(), content.length());

            // 3. 更新数据库状态为已完成
            fileInfoService.updateStatus(message.getFileId(), 2, content, ossUrl);

            // 4. 索引到 Elasticsearch
            FileDocument doc = new FileDocument();
            doc.setId(message.getFileId());
            doc.setFileName(message.getFileName());
            doc.setContent(content);
            doc.setFileType(fileType);
            doc.setOssUrl(ossUrl);

            elasticsearchClient.index(i -> i
                    .index("file_index")
                    .id(String.valueOf(doc.getId()))
                    .document(doc)
            );
            log.info("Elasticsearch 索引完成：fileId={}", message.getFileId());

            // 5. 删除临时文件
            java.io.File tempFile = new java.io.File(message.getFilePath());
            if (tempFile.exists()) {
                tempFile.delete();
            }

            log.info("文件处理完成：fileId={}", message.getFileId());

        } catch (Exception e) {
            log.error("文件处理失败：fileId=" + message.getFileId(), e);
            fileInfoService.updateStatus(message.getFileId(), 3, e.getMessage(), null);
        }
    }

    private String getFileType(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        switch (ext) {
            case "pdf": return "pdf";
            case "doc":
            case "docx": return "word";
            case "txt": return "text";
            default: return "other";
        }
    }
}