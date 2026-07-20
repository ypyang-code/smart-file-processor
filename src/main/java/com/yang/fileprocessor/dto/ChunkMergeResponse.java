package com.yang.fileprocessor.dto;

import com.yang.fileprocessor.entity.FileInfo;
import lombok.Data;

/**
 * 分片合并响应
 * <p>
 * success=true 表示合并成功且已进入异步解析流程；
 * success=false 表示合并不满足条件或过程中出错，message 包含具体原因。
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@Data
public class ChunkMergeResponse {

    /** 合并是否成功 */
    private Boolean success;

    /** 文件记录 ID */
    private Long fileId;

    /** 原始文件名 */
    private String fileName;

    /** 文件字节数 */
    private Long fileSize;

    /** 上传状态（成功时为 UPLOADED，失败时为当前状态或 MERGE_FAILED） */
    private String uploadStatus;

    /** 解析状态（成功时为 WAITING_PARSE） */
    private String parseStatus;

    /** 合并后的本地完整文件路径（仅成功时有值） */
    private String storagePath;

    /** 可读提示或错误原因 */
    private String message;

    // ========== 工厂方法 ==========

    /**
     * 合并成功
     */
    public static ChunkMergeResponse success(FileInfo fileInfo, String storagePath) {
        ChunkMergeResponse resp = new ChunkMergeResponse();
        resp.setSuccess(true);
        resp.setFileId(fileInfo.getId());
        resp.setFileName(fileInfo.getFileName());
        resp.setFileSize(fileInfo.getFileSize());
        resp.setUploadStatus("UPLOADED");
        resp.setParseStatus("WAITING_PARSE");
        resp.setStoragePath(storagePath);
        resp.setMessage("分片合并成功，已进入异步解析流程");
        return resp;
    }

    /**
     * 合并失败
     *
     * @param message 失败原因（如"当前状态不允许合并"、"MD5 校验失败"等）
     */
    public static ChunkMergeResponse fail(String message) {
        ChunkMergeResponse resp = new ChunkMergeResponse();
        resp.setSuccess(false);
        resp.setMessage(message);
        return resp;
    }

    /**
     * 合并失败（带当前文件状态信息）
     */
    public static ChunkMergeResponse fail(String message, FileInfo fileInfo) {
        ChunkMergeResponse resp = fail(message);
        if (fileInfo != null) {
            resp.setFileId(fileInfo.getId());
            resp.setFileName(fileInfo.getFileName());
            resp.setUploadStatus(fileInfo.getUploadStatus());
            resp.setParseStatus(fileInfo.getParseStatus());
        }
        return resp;
    }
}
