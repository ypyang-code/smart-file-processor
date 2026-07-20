package com.yang.fileprocessor.enums;

/**
 * 上传状态枚举
 * <p>
 * 描述文件从"创建记录"到"本地完整文件就绪"的生命周期。
 * 与 {@link ParseStatusEnum} 正交 —— 一个文件的上传状态和解析状态独立变化。
 *
 * <pre>
 * 简单上传（非分片）:
 *   INIT → UPLOADED
 *
 * 分片上传:
 *   INIT → UPLOADING → READY_TO_MERGE → MERGING → UPLOADED
 *                                              ↘ MERGE_FAILED
 * </pre>
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
public enum UploadStatusEnum {

    /** 记录已创建，等待上传（分片上传的初始状态） */
    INIT("INIT", "待上传"),

    /** 分片正在上传中 */
    UPLOADING("UPLOADING", "上传中"),

    /** 全部分片已上传完毕，等待触发合并 */
    READY_TO_MERGE("READY_TO_MERGE", "待合并"),

    /** 正在服务端合并分片 */
    MERGING("MERGING", "合并中"),

    /** 文件已就绪（完整文件存在于本地或 OSS） */
    UPLOADED("UPLOADED", "已上传"),

    /** 分片合并失败，需人工介入或重试 */
    MERGE_FAILED("MERGE_FAILED", "合并失败");

    private final String code;
    private final String desc;

    UploadStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 根据 code 查找枚举值，找不到返回 null
     */
    public static UploadStatusEnum fromCode(String code) {
        for (UploadStatusEnum e : values()) {
            if (e.code.equalsIgnoreCase(code)) {
                return e;
            }
        }
        return null;
    }
}
