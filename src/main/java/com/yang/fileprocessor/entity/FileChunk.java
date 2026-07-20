package com.yang.fileprocessor.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 分片记录实体
 * <p>
 * 记录大文件分片上传的每个分片的状态。
 * chunkIndex 从 0 开始，与客户端约定一致。
 * 唯一键 (file_md5, chunk_index) 保证同一文件的同一分片不会重复记录。
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@Data
public class FileChunk {

    private Long id;
    /** 关联 file_info.id（可为空，init 之前可能不存在） */
    private Long fileId;
    /** 完整文件的 MD5（分片查询的主键维度） */
    private String fileMd5;
    /** 分片索引，从 0 开始 */
    private Integer chunkIndex;
    /** 当前分片的实际字节数 */
    private Long chunkSize;
    /** 分片本地存储路径 */
    private String chunkPath;
    /** 上传状态（UPLOADED / FAILED） */
    private String uploadStatus;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
}
