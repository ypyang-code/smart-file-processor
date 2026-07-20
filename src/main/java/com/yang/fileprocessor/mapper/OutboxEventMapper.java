package com.yang.fileprocessor.mapper;

import com.yang.fileprocessor.entity.OutboxEvent;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 事件 Mapper
 * <p>
 * 核心能力：
 * <ul>
 *   <li>幂等插入（ON DUPLICATE KEY UPDATE → 重置为 PENDING）</li>
 *   <li>原子抢占 PENDING / 可重试 FAILED 事件</li>
 *   <li>更新事件状态</li>
 * </ul>
 *
 * @author yangyunpu
 * @since 2026-07-16
 */
@Mapper
public interface OutboxEventMapper {

    /**
     * 插入事件（幂等）
     * <p>
     * 如果 (aggregate_type, aggregate_id, event_type) 已存在：
     * - 重置 status → PENDING
     * - 重置 retry_count → 0
     * - 清空 error_message
     * - 重置 next_retry_at → NOW()
     * - 清空 locked_at / locked_by
     * <p>
     * 这意味着：重复插入同一 event 会重新排队，从零开始处理。
     */
    @Insert("INSERT INTO outbox_event " +
            "(event_id, aggregate_type, aggregate_id, event_type, payload, " +
            " status, retry_count, max_retries, next_retry_at) " +
            "VALUES " +
            "(#{eventId}, #{aggregateType}, #{aggregateId}, #{eventType}, #{payload}, " +
            " 'PENDING', 0, #{maxRetries}, NOW()) " +
            "ON DUPLICATE KEY UPDATE " +
            "event_id = VALUES(event_id), " +
            "payload = VALUES(payload), " +
            "status = 'PENDING', " +
            "retry_count = 0, " +
            "max_retries = VALUES(max_retries), " +
            "error_message = NULL, " +
            "next_retry_at = NOW(), " +
            "locked_at = NULL, " +
            "locked_by = NULL, " +
            "updated_at = NOW()")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrReset(OutboxEvent event);

    /**
     * 原子抢占待处理事件（claim）
     * <p>
     * 扫描条件：
     * <ul>
     *   <li>status = PENDING</li>
     *   <li>或 status = FAILED AND retry_count < max_retries AND next_retry_at <= NOW()</li>
     * </ul>
     * <p>
     * 抢占操作：批量 UPDATE status → PROCESSING, 设置 locked_at / locked_by。
     * 只有 UPDATE 影响的记录才被当前实例处理，实现多实例安全。
     *
     * @param lockedBy 抢占者标识（hostname:thread）
     * @param batchSize 单次最大抢占数量
     * @return 被抢占的事件列表（需在业务层根据 affected rows 二次确认）
     */
    @Update("UPDATE outbox_event SET " +
            "status = 'PROCESSING', " +
            "locked_at = NOW(), " +
            "locked_by = #{lockedBy}, " +
            "updated_at = NOW() " +
            "WHERE id IN (" +
            "  SELECT id FROM (" +
            "    SELECT id FROM outbox_event " +
            "    WHERE status = 'PENDING' " +
            "       OR (status = 'FAILED' AND retry_count < max_retries AND next_retry_at <= NOW()) " +
            "    ORDER BY next_retry_at ASC " +
            "    LIMIT #{batchSize}" +
            "  ) AS tmp" +
            ")")
    int claimEvents(@Param("lockedBy") String lockedBy,
                    @Param("batchSize") int batchSize);

    /**
     * 查询由当前实例抢占且状态为 PROCESSING 的事件
     * <p>
     * 在 claimEvents 之后调用，拿到已被占用的具体事件。
     */
    @Select("SELECT * FROM outbox_event " +
            "WHERE status = 'PROCESSING' " +
            "  AND locked_by = #{lockedBy} " +
            "  AND locked_at >= #{lockedAfter} " +
            "ORDER BY next_retry_at ASC")
    List<OutboxEvent> findClaimedBy(@Param("lockedBy") String lockedBy,
                                    @Param("lockedAfter") LocalDateTime lockedAfter);

    /**
     * 标记事件同步成功
     */
    @Update("UPDATE outbox_event SET " +
            "status = 'SUCCESS', " +
            "locked_at = NULL, " +
            "locked_by = NULL, " +
            "error_message = NULL, " +
            "updated_at = NOW() " +
            "WHERE id = #{id}")
    int markSuccess(@Param("id") Long id);

    /**
     * 标记事件同步失败
     * <p>
     * retry_count + 1，设置下次重试时间和错误信息。
     * 如果 retry_count >= max_retries → status = FAILED（最终失败，需人工补偿）。
     *
     * @param id 事件主键
     * @param errorMessage 失败原因
     * @param nextRetryAt 下次重试时间（指数退避计算）
     */
    @Update("UPDATE outbox_event SET " +
            "status = CASE WHEN retry_count + 1 >= max_retries THEN 'FAILED' ELSE 'FAILED' END, " +
            "retry_count = retry_count + 1, " +
            "error_message = #{errorMessage}, " +
            "next_retry_at = #{nextRetryAt}, " +
            "locked_at = NULL, " +
            "locked_by = NULL, " +
            "updated_at = NOW() " +
            "WHERE id = #{id}")
    int markFailed(@Param("id") Long id,
                   @Param("errorMessage") String errorMessage,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /**
     * 按聚合查询事件状态（用于业务侧判断该 file 当前是否已有待处理事件）
     */
    @Select("SELECT * FROM outbox_event " +
            "WHERE aggregate_type = #{aggregateType} " +
            "  AND aggregate_id = #{aggregateId} " +
            "  AND event_type = #{eventType}")
    OutboxEvent findByAggregate(@Param("aggregateType") String aggregateType,
                                @Param("aggregateId") String aggregateId,
                                @Param("eventType") String eventType);
}
