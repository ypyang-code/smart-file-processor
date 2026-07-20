package com.yang.fileprocessor.dto;

import lombok.Data;

/**
 * 分片上传初始化响应
 * <p>
 * 返回创建或复用的上传任务信息，客户端拿到 fileId 后可以开始上传分片。
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@Data
public class ChunkInitResponse {

    /** 文件记录 ID（复用时返回已有 ID） */
    private Long fileId;

    /** 文件 MD5 */
    private String fileMd5;

    /** 当前上传状态 */
    private String uploadStatus;

    /** 已成功上传的分片数 */
    private Integer uploadedChunks;

    /** 总分片数 */
    private Integer totalChunks;

    /** 可读提示 */
    private String message;
}
