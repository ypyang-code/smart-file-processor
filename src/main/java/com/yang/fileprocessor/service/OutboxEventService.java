package com.yang.fileprocessor.service;

import com.yang.fileprocessor.entity.OutboxEvent;
import com.yang.fileprocessor.enums.OutboxEventStatus;
import com.yang.fileprocessor.enums.OutboxEventType;
import com.yang.fileprocessor.mapper.OutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Outbox 事件服务
 * <p>
 * 职责：
 * <ul>
 *   <li>创建事件（幂等插入，重复插入 = 重置 PENDING）</li>
 *   <li>claim 待处理/可重试事件（原子抢占，多实例安全）</li>
 *   <li>标记成功 / 标记失败（含重试退避）</li>
 * </ul>
 *
 * @author yangyunpu
 * @since 2026-07-16
 */
@Service
public class OutboxEventService {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventService.class);

    /** 指数退避基数（秒）：2^retryCount × BASE，最大 10 分钟 */
    private static final long RETRY_BASE_SECONDS = 5;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    // ==================== 事件创建 ====================

    /**
     * 创建文件索引事件（幂等）
     * <p>
     * 在业务事务中调用。payload 只存最小信息，不存大段文件内容。
     * 重复插入会重置 status=PENDING / retry_count=0 / error_message=NULL。
     *
     * @param fileId  文件 ID
     * @param traceId 链路追踪 ID（可选，用于日志关联）
     */
    public void createFileIndexEvent(Long fileId, String traceId) {
        String eventId = generateEventId();
        String payload = buildPayload(fileId, traceId);

        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setAggregateType(OutboxEventType.AGGREGATE_FILE_INFO);
        event.setAggregateId(String.valueOf(fileId));
        event.setEventType(OutboxEventType.FILE_INDEX_UPSERT.getCode());
        event.setPayload(payload);
        event.setMaxRetries(5);

        int rows = outboxEventMapper.insertOrReset(event);
        if (rows > 1) {
            // ON DUPLICATE KEY UPDATE 返回 2，表示重置了已有事件
            log.info("Outbox 事件重置：fileId={}, eventId={}, eventType={}（已有事件被重新排队）",
                    fileId, eventId, OutboxEventType.FILE_INDEX_UPSERT.getCode());
        } else {
            log.info("Outbox 事件创建：fileId={}, eventId={}, eventType={}",
                    fileId, eventId, OutboxEventType.FILE_INDEX_UPSERT.getCode());
        }
    }

    // ==================== 事件抢占 ====================

    /**
     * 原子抢占一批待处理事件
     * <p>
     * 抢占条件：
     * <ul>
     *   <li>status = PENDING</li>
     *   <li>或 status = FAILED AND retry_count < max_retries AND next_retry_at <= NOW()</li>
     * </ul>
     * <p>
     * 抢占后通过 findClaimedBy 获取具体事件，确保只处理自己抢到的。
     *
     * @param lockedBy  抢占者标识
     * @param batchSize 单次最多抢占数量
     * @return 当前实例成功抢占的事件列表
     */
    public List<OutboxEvent> claimEvents(String lockedBy, int batchSize) {
        LocalDateTime claimStart = LocalDateTime.now();

        int affected = outboxEventMapper.claimEvents(lockedBy, batchSize);
        if (affected == 0) {
            return List.of();
        }

        List<OutboxEvent> claimed = outboxEventMapper.findClaimedBy(lockedBy, claimStart.minusSeconds(1));
        log.debug("Outbox 事件抢占完成：lockedBy={}, expected={}, claimed={}",
                lockedBy, affected, claimed.size());
        return claimed;
    }

    // ==================== 状态更新 ====================

    /**
     * 标记事件同步成功
     */
    public void markSuccess(Long eventId) {
        outboxEventMapper.markSuccess(eventId);
        log.debug("Outbox 事件同步成功：eventId={}", eventId);
    }

    /**
     * 标记事件同步失败（含重试退避）
     * <p>
     * 退避策略：next_retry_at = NOW() + 5s × 2^retryCount，上限 10 分钟。
     * retry_count 达到 max_retries 后保持 FAILED 状态，等待人工补偿。
     *
     * @param event        事件实体（需含当前 retry_count 和 max_retries）
     * @param errorMessage 失败原因
     */
    public void markFailed(OutboxEvent event, String errorMessage) {
        int currentRetry = event.getRetryCount() != null ? event.getRetryCount() : 0;
        int maxRetries = event.getMaxRetries() != null ? event.getMaxRetries() : 5;
        int nextRetryCount = currentRetry + 1;

        LocalDateTime nextRetryAt = computeNextRetryAt(nextRetryCount);

        outboxEventMapper.markFailed(event.getId(), errorMessage, nextRetryAt);

        if (nextRetryCount >= maxRetries) {
            log.warn("Outbox 事件最终失败（已达最大重试次数={}）：eventId={}, aggregateId={}, error={}",
                    maxRetries, event.getEventId(), event.getAggregateId(), errorMessage);
        } else {
            log.info("Outbox 事件同步失败（将重试 {}/{}）：eventId={}, aggregateId={}, nextRetryAt={}, error={}",
                    nextRetryCount, maxRetries, event.getEventId(),
                    event.getAggregateId(), nextRetryAt, errorMessage);
        }
    }

    // ==================== 私有工具方法 ====================

    /**
     * 生成事件唯一 ID
     */
    private String generateEventId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 构建最小 payload（不存大段文件内容）
     */
    private String buildPayload(Long fileId, String traceId) {
        return String.format("{\"fileId\":%d,\"traceId\":\"%s\"}",
                fileId, traceId != null ? traceId : "");
    }

    /**
     * 计算下次重试时间（指数退避）
     * <p>
     * nextRetryAt = NOW() + 5s × 2^retryCount，上限 10 分钟。
     */
    LocalDateTime computeNextRetryAt(int retryCount) {
        long delaySeconds = RETRY_BASE_SECONDS * (1L << Math.min(retryCount, 7));
        long maxSeconds = 600; // 10 分钟上限
        delaySeconds = Math.min(delaySeconds, maxSeconds);
        return LocalDateTime.now().plusSeconds(delaySeconds);
    }
}
