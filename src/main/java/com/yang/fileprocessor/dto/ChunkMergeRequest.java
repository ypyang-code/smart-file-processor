package com.yang.fileprocessor.dto;

import lombok.Data;

/**
 * 分片合并请求
 * <p>
 * 客户端在所有分片上传完成后调用 POST /api/file/chunk/merge，
 * 仅需传入 fileMd5，服务端根据 MD5 查找对应的上传任务和全部分片。
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@Data
public class ChunkMergeRequest {

    /** 文件 MD5（32 位十六进制，必填） */
    private String fileMd5;
}
