package com.yang.fileprocessor.enums;

/**
 * 解析状态枚举
 * <p>
 * 描述文件从"等待解析"到"解析完成/失败"的生命周期。
 * 与 {@link UploadStatusEnum} 正交 —— 文件必须先 UPLOADED，才能进入解析流程。
 *
 * <pre>
 * 正常流程:
 *   NOT_PARSED → WAITING_PARSE → PARSING → PARSE_SUCCESS
 *
 * 失败流程:
 *   NOT_PARSED → WAITING_PARSE → PARSING → PARSE_FAILED
 *                                                ↓ (人工/自动重试)
 *                                          WAITING_PARSE → ...
 * </pre>
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
public enum ParseStatusEnum {

    /** 尚未进入解析队列 */
    NOT_PARSED("NOT_PARSED", "未解析"),

    /** 已投递 MQ，等待消费 */
    WAITING_PARSE("WAITING_PARSE", "等待解析"),

    /** Consumer 正在解析中 */
    PARSING("PARSING", "解析中"),

    /** 解析成功（OSS 上传 + 文本提取 + ES 索引全部完成） */
    PARSE_SUCCESS("PARSE_SUCCESS", "解析成功"),

    /** 解析失败（需重试或人工处理） */
    PARSE_FAILED("PARSE_FAILED", "解析失败");

    private final String code;
    private final String desc;

    ParseStatusEnum(String code, String desc) {
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
    public static ParseStatusEnum fromCode(String code) {
        for (ParseStatusEnum e : values()) {
            if (e.code.equalsIgnoreCase(code)) {
                return e;
            }
        }
        return null;
    }
}
