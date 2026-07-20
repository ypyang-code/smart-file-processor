package com.yang.fileprocessor.dto;

import com.yang.fileprocessor.entity.FileInfo;
import lombok.Data;

/**
 * MD5 秒传校验响应
 * <p>
 * instantUpload=true 表示服务端已存在该文件（MD5 + 文件大小一致，且上传状态为 UPLOADED），
 * 客户端可直接复用已有记录，无需再次上传。
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@Data
public class Md5CheckResponse {

    /** 是否可以秒传（true=跳过上传，false=需要正常上传或分片上传） */
    private Boolean instantUpload;

    /** 已有文件的 ID（仅 instantUpload=true 时有值） */
    private Long fileId;

    /** 已有文件的原始文件名 */
    private String fileName;

    /** 已有文件的字节数 */
    private Long fileSize;

    /** 已有文件的 OSS 公网访问 URL（可能为空，取决于是否已完成 OSS 上传） */
    private String ossUrl;

    /** 已有文件的上传状态 */
    private String uploadStatus;

    /** 已有文件的解析状态 */
    private String parseStatus;

    /** 可读的提示信息（如 "文件已存在，无需重复上传" / "文件不存在，请继续上传"） */
    private String message;

    // ========== 工厂方法 ==========

    public static Md5CheckResponse hit(FileInfo fileInfo) {
        Md5CheckResponse resp = new Md5CheckResponse();
        resp.setInstantUpload(true);
        resp.setFileId(fileInfo.getId());
        resp.setFileName(fileInfo.getFileName());
        resp.setFileSize(fileInfo.getFileSize());
        resp.setOssUrl(fileInfo.getOssUrl());
        resp.setUploadStatus(fileInfo.getUploadStatus());
        resp.setParseStatus(fileInfo.getParseStatus());
        resp.setMessage("文件已存在，无需重复上传");
        return resp;
    }

    public static Md5CheckResponse miss() {
        Md5CheckResponse resp = new Md5CheckResponse();
        resp.setInstantUpload(false);
        resp.setMessage("文件不存在，请继续上传");
        return resp;
    }
}
