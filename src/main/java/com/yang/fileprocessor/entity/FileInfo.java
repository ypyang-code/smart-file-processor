package com.yang.fileprocessor.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class FileInfo {
    // ========== 原有字段（保持不变） ==========
    private Long id;
    private String fileName;
    private String fileType;
    private Long fileSize;

    /** 保留兼容，新代码优先使用 uploadStatus + parseStatus（0=待处理, 1=处理中, 2=已完成, 3=失败） */
    private Integer status;

    private String ossUrl;
    private String content;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ========== Phase 2 新增字段 ==========

    /** 文件 MD5（32 位十六进制），用于秒传校验和去重 */
    private String fileMd5;

    /** 本地存储路径（简单上传时指向完整文件，分片上传时指向合并后的文件） */
    private String storagePath;

    /** 上传状态（INIT / UPLOADING / MERGING / UPLOADED / MERGE_FAILED） */
    private String uploadStatus;

    /** 解析状态（NOT_PARSED / WAITING_PARSE / PARSING / PARSE_SUCCESS / PARSE_FAILED） */
    private String parseStatus;

    /** 总分片数（非分片上传默认为 1） */
    private Integer totalChunks;

    /** 已上传分片数（非分片上传默认为 1） */
    private Integer uploadedChunks;

    /** 是否分片上传（0=否, 1=是） */
    private Boolean isChunked;

    /** 分片合并完成时间 */
    private LocalDateTime mergeTime;
}