package com.yang.fileprocessor.dto;

import lombok.Data;

/**
 * MD5 秒传校验请求
 * <p>
 * 客户端在上传文件前，先计算文件 MD5 和文件大小，
 * 将结果发送到 POST /api/file/chunk/check 检查是否已存在。
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@Data
public class Md5CheckRequest {

    /** 文件 MD5（32 位十六进制字符串，不区分大小写，建议统一转小写） */
    private String fileMd5;

    /** 文件字节数，必须 > 0，与 MD5 联合校验防止哈希碰撞 */
    private Long fileSize;

    /** 原始文件名（可选，仅用于日志和调试） */
    private String originalFilename;
}
