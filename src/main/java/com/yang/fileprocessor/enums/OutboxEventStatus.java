package com.yang.fileprocessor.enums;

/**
 * Outbox 事件状态枚举
 *
 * <pre>
 * 正常流程:
 *   PENDING → PROCESSING → SUCCESS
 *
 * 失败重试:
 *   PENDING → PROCESSING → FAILED → (等待 next_retry_at) → PROCESSING → SUCCESS
 *
 * 最终失败:
 *   PENDING → PROCESSING → FAILED → ... → FAILED (retry_count >= max_retries，等待人工补偿)
 * </pre>
 *
 * @author yangyunpu
 * @since 2026-07-16
 */
public enum OutboxEventStatus {

    /** 等待调度 */
    PENDING("PENDING", "待处理"),

    /** 已被调度任务抢占，正在处理中 */
    PROCESSING("PROCESSING", "处理中"),

    /** 同步成功 */
    SUCCESS("SUCCESS", "成功"),

    /** 同步失败（retry_count < max_retries 时可重试） */
    FAILED("FAILED", "失败");

    private final String code;
    private final String desc;

    OutboxEventStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static OutboxEventStatus fromCode(String code) {
        for (OutboxEventStatus e : values()) {
            if (e.code.equalsIgnoreCase(code)) {
                return e;
            }
        }
        return null;
    }
}
