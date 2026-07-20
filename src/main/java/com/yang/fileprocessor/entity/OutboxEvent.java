package com.yang.fileprocessor.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * Transactional Outbox 事件实体
 * <p>
 * 与业务数据在同一 MySQL 事务中原子写入，由定时任务异步同步到 Elasticsearch。
 * 通过 event_id 实现全局幂等，通过 (aggregate_type, aggregate_id, event_type)
 * 唯一约束保证同一事件只保留最新一条。
 *
 * @author yangyunpu
 * @since 2026-07-16
 */
@Data
public class OutboxEvent {

    private Long id;
    /** 事件唯一 ID（UUID 去横线） */
    private String eventId;
    /** 聚合类型：FILE_INFO */
    private String aggregateType;
    /** 聚合 ID：fileId 字符串 */
    private String aggregateId;
    /** 事件类型：FILE_INDEX_UPSERT / FILE_INDEX_DELETE */
    private String eventType;
    /** 最小事件载荷（不存大段文件内容） */
    private String payload;
    /** PENDING / PROCESSING / SUCCESS / FAILED */
    private String status;
    /** 已重试次数 */
    private Integer retryCount;
    /** 最大重试次数 */
    private Integer maxRetries;
    /** 下次可重试时间 */
    private LocalDateTime nextRetryAt;
    /** 被抢占的时间 */
    private LocalDateTime lockedAt;
    /** 抢占实例标识 */
    private String lockedBy;
    /** 最近一次失败原因 */
    private String errorMessage;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
}
