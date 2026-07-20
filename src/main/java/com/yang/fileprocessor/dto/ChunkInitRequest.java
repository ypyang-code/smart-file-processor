package com.yang.fileprocessor.dto;

import lombok.Data;

/**
 * 分片上传初始化请求
 * <p>
 * 客户端计算好文件 MD5 并确定分片策略后，调用 POST /api/file/chunk/init
 * 创建或复用上传任务。
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@Data
public class ChunkInitRequest {

    /** 文件 MD5（32 位十六进制，必填） */
    private String fileMd5;

    /** 文件总字节数（必填，> 0） */
    private Long fileSize;

    /** 原始文件名（必填） */
    private String originalFilename;

    /** 总分片数（必填，> 0） */
    private Integer totalChunks;

    /** 单个分片大小（必填，> 0，最后一个分片可能小于此值） */
    private Long chunkSize;
}
