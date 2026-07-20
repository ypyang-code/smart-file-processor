package com.yang.fileprocessor.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.yang.fileprocessor.entity.FileDocument;
import com.yang.fileprocessor.entity.FileInfo;
import com.yang.fileprocessor.entity.OutboxEvent;
import com.yang.fileprocessor.enums.OutboxEventType;
import com.yang.fileprocessor.mapper.FileInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Outbox 异步同步调度器
 * <p>
 * 定时扫描 {@code outbox_event} 表中 PENDING 或可重试 FAILED 事件，
 * 原子抢占后根据 aggregate_id 回查 MySQL 最新 file_info，
 * 构造 ES 文档并通过 {@code _id = fileId} 做幂等 upsert。
 *
 * <h3>一致性语义</h3>
 * <p>本调度器实现的是 <b>至少一次执行 + 幂等处理 + 最终一致性</b>。
 * ES 文档以 fileId 作为 _id，重复 index 操作为 upsert，不会产生重复文档。
 * 调度器可能因为进程崩溃而重复处理同一事件，但 ES 结果始终正确。
 *
 * <h3>多实例安全</h3>
 * <p>通过 DB 级别的 claim（UPDATE 原子抢占）保证同一事件只被一个实例处理。
 *
 * @author yangyunpu
 * @since 2026-07-16
 */
@Component
public class OutboxSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxSyncScheduler.class);

    private static final String ES_INDEX_NAME = "file_index";

    /** 单次调度最多处理的事件数 */
    private static final int BATCH_SIZE = 20;

    @Autowired
    private OutboxEventService outboxEventService;

    @Autowired
    private FileInfoMapper fileInfoMapper;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    /**
     * 定时扫描并同步 ES
     * <p>
     * 固定间隔 5 秒执行。在测试环境中可通过配置属性调整。
     */
    @Scheduled(fixedDelayString = "${outbox.sync.interval-ms:5000}")
    public void syncToElasticsearch() {
        String lockedBy = getLockedBy();

        // 1. 原子抢占事件
        List<OutboxEvent> events = outboxEventService.claimEvents(lockedBy, BATCH_SIZE);
        if (events.isEmpty()) {
            return;
        }

        log.debug("OutboxSyncScheduler 抢占到 {} 个事件，lockedBy={}", events.size(), lockedBy);

        // 2. 逐个处理
        for (OutboxEvent event : events) {
            processEvent(event);
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 处理单个事件
     */
    private void processEvent(OutboxEvent event) {
        Long fileId;
        try {
            fileId = Long.parseLong(event.getAggregateId());
        } catch (NumberFormatException e) {
            log.error("Outbox 事件 aggregate_id 非法：eventId={}, aggregateId={}",
                    event.getEventId(), event.getAggregateId());
            outboxEventService.markFailed(event, "aggregate_id 格式错误: " + event.getAggregateId());
            return;
        }

        String eventType = event.getEventType();
        try {
            if (OutboxEventType.FILE_INDEX_UPSERT.getCode().equals(eventType)) {
                syncFileIndexUpsert(fileId);
            } else if (OutboxEventType.FILE_INDEX_DELETE.getCode().equals(eventType)) {
                syncFileIndexDelete(fileId);
            } else {
                log.warn("Outbox 事件类型未知：eventId={}, eventType={}", event.getEventId(), eventType);
                outboxEventService.markFailed(event, "未知事件类型: " + eventType);
                return;
            }

            outboxEventService.markSuccess(event.getId());
            log.info("Outbox ES 同步成功：eventId={}, fileId={}, eventType={}",
                    event.getEventId(), fileId, eventType);

        } catch (Exception e) {
            log.error("Outbox ES 同步失败：eventId={}, fileId={}, eventType={}, error={}",
                    event.getEventId(), fileId, eventType, e.getMessage());
            outboxEventService.markFailed(event, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 同步 FILE_INDEX_UPSERT：回查 MySQL → 构造 ES 文档 → upsert
     * <p>
     * 使用 fileId 作为 ES _id，保证幂等：重复 index 不会产生重复文档。
     */
    private void syncFileIndexUpsert(Long fileId) throws Exception {
        // 回查 MySQL 最新 file_info（拿到完整内容，包括 content）
        FileInfo fileInfo = fileInfoMapper.findById(fileId);
        if (fileInfo == null) {
            log.warn("Outbox ES 同步跳过：file_info 记录不存在 fileId={}", fileId);
            return;
        }

        // 构造 ES 文档
        FileDocument doc = new FileDocument();
        doc.setId(fileInfo.getId());
        doc.setFileName(fileInfo.getFileName());
        doc.setContent(fileInfo.getContent());
        doc.setFileType(fileInfo.getFileType());
        doc.setOssUrl(fileInfo.getOssUrl());

        // ES upsert（_id = fileId，幂等）
        elasticsearchClient.index(i -> i
                .index(ES_INDEX_NAME)
                .id(String.valueOf(fileId))
                .document(doc)
        );
        log.debug("ES upsert 完成：fileId={}, fileName={}", fileId, fileInfo.getFileName());
    }

    /**
     * 同步 FILE_INDEX_DELETE：从 ES 删除文档
     */
    private void syncFileIndexDelete(Long fileId) throws Exception {
        elasticsearchClient.delete(d -> d
                .index(ES_INDEX_NAME)
                .id(String.valueOf(fileId))
        );
        log.debug("ES delete 完成：fileId={}", fileId);
    }

    /**
     * 生成抢占者标识
     */
    private String getLockedBy() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        return host + ":" + Thread.currentThread().getName();
    }
}
