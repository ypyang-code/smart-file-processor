package com.yang.fileprocessor.entity;

/**
 * 分片上传状态枚举
 * <p>
 * 用于 file_chunk 表的 upload_status 字段。
 * 与 UploadStatusEnum（文件级）不同，这是分片级别的状态。
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
public enum FileChunkStatus {

    /** 分片已成功上传到本地磁盘 */
    UPLOADED("UPLOADED"),

    /** 分片上传失败 */
    FAILED("FAILED");

    private final String code;

    FileChunkStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
