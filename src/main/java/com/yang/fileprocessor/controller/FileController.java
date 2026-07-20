package com.yang.fileprocessor.controller;

import com.yang.fileprocessor.dto.FileUploadMessage;
import com.yang.fileprocessor.dto.Result;
import com.yang.fileprocessor.entity.FileInfo;
import com.yang.fileprocessor.service.FileInfoService;
import com.yang.fileprocessor.service.FileUploadProducer;
import com.yang.fileprocessor.utils.FileTypeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

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
    public Result upload(@RequestParam("file") MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename();
        String fileType = FileTypeUtil.getFileType(originalName);
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
    }

    /**
     * 文件列表（兼容无参数调用和分页调用）
     * <ul>
     *   <li>无参数：返回完整数组（向后兼容旧前端）</li>
     *   <li>带 page/size：返回 {@link com.yang.fileprocessor.dto.PageResult} 分页结构</li>
     * </ul>
     *
     * @param page 页码（从 1 开始，默认 1）
     * @param size 每页条数（默认 20，最大 100）
     */
    @GetMapping("/list")
    public Result list(@RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size) {
        // 无参数调用：向后兼容，返回完整数组
        if (page == null && size == null) {
            return Result.success(fileInfoService.getAll());
        }

        // 有参数调用：分页模式，边界保护
        int p = (page != null && page >= 1) ? page : 1;
        int s;
        if (size == null || size < 1) {
            s = 20;
        } else {
            s = Math.min(size, 100);
        }

        return Result.success(fileInfoService.getPage(p, s));
    }

    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        return Result.success(fileInfoService.getById(id));
    }

}