package com.yang.fileprocessor.controller;

import com.yang.fileprocessor.dto.FileUploadMessage;
import com.yang.fileprocessor.dto.Result;
import com.yang.fileprocessor.entity.FileInfo;
import com.yang.fileprocessor.service.FileInfoService;
import com.yang.fileprocessor.service.FileUploadProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@RestController
@RequestMapping("/api/file")
public class FileController {

    @Autowired
    private FileInfoService fileInfoService;

    @Autowired
    private FileUploadProducer fileUploadProducer;

    // 临时文件保存目录
    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    @PostMapping("/upload")
    public Result upload(@RequestParam("file") MultipartFile file) {
        try {
            String originalName = file.getOriginalFilename();
            String fileType = getFileType(originalName);
            long fileSize = file.getSize();

            // 1. 保存文件信息到数据库（状态：待处理）
            FileInfo fileInfo = fileInfoService.saveFileInfo(originalName, fileType, fileSize);

            // 2. 保存文件到本地临时目录
            File dir = new File(uploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String tempFilePath = uploadDir + File.separator + fileInfo.getId() + "_" + originalName;
            file.transferTo(new File(tempFilePath));

            // 3. 发送消息到 RabbitMQ 队列（异步处理）
            FileUploadMessage message = new FileUploadMessage();
            message.setFileId(fileInfo.getId());
            message.setFilePath(tempFilePath);
            message.setFileName(originalName);
            fileUploadProducer.sendUploadTask(message);

            // 4. 立即返回，不等 OSS 上传完成
            return Result.success("文件已提交处理，fileId=" + fileInfo.getId());

        } catch (Exception e) {
            return Result.error("上传失败：" + e.getMessage());
        }
    }

    @GetMapping("/list")
    public Result list() {
        return Result.success(fileInfoService.getAll());
    }

    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        return Result.success(fileInfoService.getById(id));
    }

    private String getFileType(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        switch (ext) {
            case "pdf": return "pdf";
            case "doc":
            case "docx": return "word";
            case "jpg":
            case "jpeg":
            case "png": return "image";
            case "txt": return "text";
            default: return "other";
        }
    }
}