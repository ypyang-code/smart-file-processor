-- ============================================================
-- Smart File Processor · outbox_event 表（完整建表）
-- 适用于: 新环境首次建库 或 已有库增量执行
-- 数据库: file_processor (MySQL 8.0+)
-- 字符集: utf8mb4
-- 创建时间: 2026-07-16
-- 说明:   Transactional Outbox Pattern — 事件表
-- ============================================================

USE file_processor;

DROP TABLE IF EXISTS outbox_event;

CREATE TABLE outbox_event (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
    event_id        VARCHAR(64)   NOT NULL              COMMENT '事件唯一 ID（UUID，去横线）',
    aggregate_type  VARCHAR(50)   NOT NULL              COMMENT '聚合类型: FILE_INFO',
    aggregate_id    VARCHAR(64)   NOT NULL              COMMENT '聚合 ID: fileId 字符串',
    event_type      VARCHAR(50)   NOT NULL              COMMENT '事件类型: FILE_INDEX_UPSERT / FILE_INDEX_DELETE',
    payload         TEXT          DEFAULT NULL          COMMENT '最小事件载荷（不存大段文件内容，仅 fileId / traceId 等）',
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                                                        COMMENT 'PENDING / PROCESSING / SUCCESS / FAILED',
    retry_count     INT           NOT NULL DEFAULT 0    COMMENT '已重试次数',
    max_retries     INT           NOT NULL DEFAULT 5    COMMENT '最大重试次数',
    next_retry_at   DATETIME      DEFAULT CURRENT_TIMESTAMP
                                                        COMMENT '下次可重试时间',
    locked_at       DATETIME      DEFAULT NULL          COMMENT '被抢占的时间',
    locked_by       VARCHAR(64)   DEFAULT NULL          COMMENT '抢占实例标识（hostname:thread）',
    error_message   TEXT          DEFAULT NULL          COMMENT '最近一次失败原因',
    created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- event_id 全局唯一，用于幂等追踪
    UNIQUE KEY uk_event_id (event_id),

    -- 同一聚合 + 同一事件类型只保留最新一条
    UNIQUE KEY uk_aggregate_event (aggregate_type, aggregate_id, event_type),

    -- 调度任务扫描索引
    INDEX idx_status_retry (status, next_retry_at, created_at),

    -- 按聚合查询事件状态
    INDEX idx_aggregate (aggregate_type, aggregate_id)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Transactional Outbox 事件表';
