package com.yang.fileprocessor.dto;

import lombok.Data;

/**
 * 分片上传响应
 * <p>
 * 单个分片上传成功后的响应，包含当前进度信息。
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@Data
public class ChunkUploadResponse {

    /** 文件 MD5 */
    private String fileMd5;

    /** 当前上传的分片索引 */
    private Integer chunkIndex;

    /** 已成功上传的分片数（从 file_chunk 表统计，权威值） */
    private Integer uploadedChunks;

    /** 总分片数 */
    private Integer totalChunks;

    /** 文件级上传状态（UPLOADING / READY_TO_MERGE） */
    private String uploadStatus;

    /** 可读提示 */
    private String message;
}
