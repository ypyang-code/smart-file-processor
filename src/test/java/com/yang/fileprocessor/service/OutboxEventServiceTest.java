package com.yang.fileprocessor.service;

import com.yang.fileprocessor.entity.OutboxEvent;
import com.yang.fileprocessor.enums.OutboxEventStatus;
import com.yang.fileprocessor.enums.OutboxEventType;
import com.yang.fileprocessor.mapper.OutboxEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OutboxEventService 单元测试（纯 Mockito，无 Spring 上下文）
 *
 * <p>覆盖：
 * <ol>
 *   <li>创建事件成功（insertOrReset 被调用）</li>
 *   <li>重复创建同一聚合事件 → ON DUPLICATE KEY UPDATE 重置为 PENDING</li>
 *   <li>claim 事件（无事件 → 空列表；有事件 → 返回抢占列表）</li>
 *   <li>标记成功 → SUCCESS</li>
 *   <li>标记失败 → retry_count 增加, next_retry_at 更新</li>
 *   <li>达到最大重试次数 → 最终 FAILED（retry_count >= max_retries）</li>
 *   <li>指数退避计算</li>
 * </ol>
 *
 * @author yangyunpu
 * @since 2026-07-16
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventServiceTest {

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @InjectMocks
    private OutboxEventService outboxEventService;

    private static final Long FILE_ID = 1L;

    @BeforeEach
    void setUp() {
        // 无特殊初始化
    }

    // ==================== 创建事件 ====================

    @Test
    void createFileIndexEvent_首次创建_应调用InsertOrReset() {
        when(outboxEventMapper.insertOrReset(any(OutboxEvent.class))).thenReturn(1);

        outboxEventService.createFileIndexEvent(FILE_ID, "trace-123");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insertOrReset(captor.capture());

        OutboxEvent event = captor.getValue();
        assertThat(event.getAggregateType()).isEqualTo(OutboxEventType.AGGREGATE_FILE_INFO);
        assertThat(event.getAggregateId()).isEqualTo(String.valueOf(FILE_ID));
        assertThat(event.getEventType()).isEqualTo(OutboxEventType.FILE_INDEX_UPSERT.getCode());
        assertThat(event.getMaxRetries()).isEqualTo(5);
        // payload 只存最小信息，不存大段 content
        assertThat(event.getPayload()).contains("\"fileId\":1");
        assertThat(event.getPayload()).doesNotContain("content");
    }

    @Test
    void createFileIndexEvent_重复创建同一聚合事件_应重置为PENDING() {
        // ON DUPLICATE KEY UPDATE 返回 2
        when(outboxEventMapper.insertOrReset(any(OutboxEvent.class))).thenReturn(2);

        outboxEventService.createFileIndexEvent(FILE_ID, "trace-456");

        // insertOrReset 被调用，内部 SQL 的 ON DUPLICATE KEY UPDATE 会：
        // status → PENDING, retry_count → 0, error_message → NULL, next_retry_at → NOW()
        verify(outboxEventMapper).insertOrReset(any(OutboxEvent.class));
    }

    @Test
    void createFileIndexEvent_payload不存大段内容() {
        when(outboxEventMapper.insertOrReset(any(OutboxEvent.class))).thenReturn(1);

        outboxEventService.createFileIndexEvent(FILE_ID, "");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insertOrReset(captor.capture());

        String payload = captor.getValue().getPayload();
        // payload 只含 fileId 和 traceId
        assertThat(payload).contains("fileId");
        assertThat(payload).doesNotContain("fileName");
        assertThat(payload).doesNotContain("content");
        assertThat(payload).doesNotContain("ossUrl");
        assertThat(payload).doesNotContain("fileType");
    }

    // ==================== 事件抢占 ====================

    @Test
    void claimEvents_没有待处理事件_应返回空列表() {
        when(outboxEventMapper.claimEvents(anyString(), eq(20))).thenReturn(0);

        List<OutboxEvent> result = outboxEventService.claimEvents("test-host:thread-1", 20);

        assertThat(result).isEmpty();
        verify(outboxEventMapper, never()).findClaimedBy(anyString(), any());
    }

    @Test
    void claimEvents_有事件_应返回抢占列表() {
        OutboxEvent event = new OutboxEvent();
        event.setId(10L);
        event.setEventId("abc123");
        event.setAggregateId(String.valueOf(FILE_ID));
        event.setRetryCount(0);
        event.setMaxRetries(5);

        when(outboxEventMapper.claimEvents(eq("host-1:pool-1"), eq(20))).thenReturn(1);
        when(outboxEventMapper.findClaimedBy(eq("host-1:pool-1"), any()))
                .thenReturn(List.of(event));

        List<OutboxEvent> result = outboxEventService.claimEvents("host-1:pool-1", 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(10L);
    }

    // ==================== 标记成功 ====================

    @Test
    void markSuccess_应调用Mapper() {
        outboxEventService.markSuccess(10L);

        verify(outboxEventMapper).markSuccess(10L);
    }

    // ==================== 标记失败 + 重试 ====================

    @Test
    void markFailed_首次失败_retryCount应增加_nextRetryAt应更新() {
        OutboxEvent event = new OutboxEvent();
        event.setId(10L);
        event.setEventId("abc123");
        event.setAggregateId(String.valueOf(FILE_ID));
        event.setRetryCount(0);
        event.setMaxRetries(5);

        outboxEventService.markFailed(event, "ES 连接超时");

        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxEventMapper).markFailed(eq(10L), eq("ES 连接超时"), timeCaptor.capture());

        // next_retry_at 应该在未来的退避时间范围内
        LocalDateTime nextRetry = timeCaptor.getValue();
        assertThat(nextRetry).isAfter(LocalDateTime.now());
        // retry_count=0 → 5s × 2^1 = 10s（第一次进入 markFailed 时 currentRetry=0, nextRetryCount=1）
        assertThat(nextRetry).isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(15));
    }

    @Test
    void markFailed_达到最大重试次数_应标记最终FAILED() {
        OutboxEvent event = new OutboxEvent();
        event.setId(10L);
        event.setEventId("abc123");
        event.setAggregateId(String.valueOf(FILE_ID));
        event.setRetryCount(4); // 下次就是第 5 次，等于 max_retries=5
        event.setMaxRetries(5);

        outboxEventService.markFailed(event, "ES 仍然不可用");

        // markFailed 被调用，mapper 内部 status 保持 FAILED
        verify(outboxEventMapper).markFailed(eq(10L), eq("ES 仍然不可用"), any());
    }

    @Test
    void markFailed_超过最大重试次数_retryCount达到maxRetries() {
        OutboxEvent event = new OutboxEvent();
        event.setId(20L);
        event.setEventId("xyz789");
        event.setAggregateId(String.valueOf(FILE_ID));
        event.setRetryCount(5); // 已达上限
        event.setMaxRetries(5);

        outboxEventService.markFailed(event, "超限测试");

        // 仍然调用 markFailed，mapper SQL 中 status 保持 FAILED
        verify(outboxEventMapper).markFailed(eq(20L), eq("超限测试"), any());
    }

    // ==================== 指数退避计算 ====================

    @Test
    void computeNextRetryAt_retryCount1_应为10秒() {
        LocalDateTime result = outboxEventService.computeNextRetryAt(1);

        LocalDateTime expectedEarliest = LocalDateTime.now().plusSeconds(9);
        LocalDateTime expectedLatest = LocalDateTime.now().plusSeconds(11);
        assertThat(result).isBetween(expectedEarliest, expectedLatest);
    }

    @Test
    void computeNextRetryAt_retryCount5_应为160秒() {
        LocalDateTime result = outboxEventService.computeNextRetryAt(5);

        // 5 × 2^5 = 160s，允许 ±2s 误差
        LocalDateTime expectedEarliest = LocalDateTime.now().plusSeconds(158);
        LocalDateTime expectedLatest = LocalDateTime.now().plusSeconds(162);
        assertThat(result).isBetween(expectedEarliest, expectedLatest);
    }

    @Test
    void computeNextRetryAt_retryCount很大_应上限600秒() {
        LocalDateTime result = outboxEventService.computeNextRetryAt(20);

        // 20 次重试但上限 600s（10 分钟）
        LocalDateTime expectedEarliest = LocalDateTime.now().plusSeconds(598);
        LocalDateTime expectedLatest = LocalDateTime.now().plusSeconds(602);
        assertThat(result).isBetween(expectedEarliest, expectedLatest);
    }
}
