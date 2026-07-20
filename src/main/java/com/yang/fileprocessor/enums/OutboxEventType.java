package com.yang.fileprocessor.enums;

/**
 * Outbox 事件类型枚举
 * <p>
 * 定义业务事件类型，与 ES 操作一一对应。
 *
 * @author yangyunpu
 * @since 2026-07-16
 */
public enum OutboxEventType {

    /** 文件索引更新或插入（ES upsert） */
    FILE_INDEX_UPSERT("FILE_INDEX_UPSERT", "文件索引更新"),

    /** 文件索引删除 */
    FILE_INDEX_DELETE("FILE_INDEX_DELETE", "文件索引删除");

    private final String code;
    private final String desc;

    OutboxEventType(String code, String desc) {
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
     * 聚合类型常量（所有 outbox 事件目前都针对 FILE_INFO 聚合）
     */
    public static final String AGGREGATE_FILE_INFO = "FILE_INFO";
}
