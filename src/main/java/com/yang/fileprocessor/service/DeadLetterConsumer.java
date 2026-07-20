package com.yang.fileprocessor.service;

import com.rabbitmq.client.Channel;
import com.yang.fileprocessor.config.RabbitMqConfig;
import com.yang.fileprocessor.dto.FileUploadMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 死信队列消费者（Phase 1 闭环 3）
 *
 * <h3>职责</h3>
 * <p>监听 {@code file.upload.dlq}，记录死信信息到日志和 DB。不做自动重试/补偿。</p>
 *
 * <h3>ACK 策略</h3>
 * <p>内部 catch 所有业务异常 + finally basicAck，确保 DLQ 不堆积。
 * basicAck IOException 只记录日志不向外抛，确保不会触发 Spring Retry。</p>
 *
 * <h3>排查方式</h3>
 * <ul>
 *   <li>应用日志搜索"【死信消息】"</li>
 *   <li>DB: {@code SELECT * FROM file_info WHERE parse_status='PARSE_FAILED'}</li>
 * </ul>
 */
@Service
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    @Autowired
    private FileInfoService fileInfoService;

    @RabbitListener(queues = RabbitMqConfig.FILE_UPLOAD_DLQ)
    public void handleDeadLetter(
            FileUploadMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
            @Header(AmqpHeaders.CONSUMER_QUEUE) String dlqConsumerQueue,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String receivedRoutingKey,
            Message amqpMessage) {

        Long fileId = message.getFileId();
        String fileName = message.getFileName();
        String filePath = message.getFilePath();

        try {
            // 提取 x-death header（死因详情）
            List<Map<String, ?>> xDeath = getXDeath(amqpMessage);

            // 从 x-death 中读取原始来源队列（即业务队列 file.upload.queue）
            String originalQueue = extractOriginalQueue(xDeath);

            // 格式化死因
            String deathReason = formatDeathReason(xDeath);

            log.warn("【死信消息】fileId={}, fileName={}, filePath={}, " +
                      "dlqConsumerQueue={}, originalQueue={}, receivedRoutingKey={}, " +
                      "deathReason={}, xDeath={}",
                      fileId, fileName, filePath,
                      dlqConsumerQueue, originalQueue, receivedRoutingKey,
                      deathReason, xDeath);

            // 补充 DB 失败标记（如果闭环 2 已写 PARSE_FAILED，这里追加死信原因）
            fileInfoService.updateStatus(fileId, 3,
                    "消息进入死信队列: " + deathReason, null);

        } catch (Exception e) {
            // ★ 内部 catch 所有异常，不允许向外抛
            // 否则可能触发 Spring Retry 导致 DLQ 消息重复消费
            log.error("DeadLetterConsumer 处理异常(消息仍会被ACK): " +
                      "fileId={}, fileName={}", fileId, fileName, e);
        } finally {
            // ★ 无论如何都要 ACK，防止 DLQ 消息堆积
            try {
                channel.basicAck(deliveryTag, false);
            } catch (IOException ackEx) {
                log.error("DeadLetterConsumer basicAck IO异常(消息保持unacked): " +
                          "fileId={}, deliveryTag={}", fileId, deliveryTag, ackEx);
                // 不向外抛：basicAck 失败是极低概率事件
                // 消息保持 unacked，Channel 关闭后重新投递 DLQ，再次进入本方法
            }
        }
    }

    /**
     * 从 AMQP Message 提取 x-death header
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, ?>> getXDeath(Message message) {
        return (List<Map<String, ?>>) message.getMessageProperties()
                .getHeader("x-death");
    }

    /**
     * 从 x-death 第一条记录中提取原始来源队列名
     */
    private String extractOriginalQueue(List<Map<String, ?>> xDeath) {
        if (xDeath == null || xDeath.isEmpty()) {
            return "未知";
        }
        Object queue = xDeath.get(0).get("queue");
        return queue != null ? queue.toString() : "未知";
    }

    /**
     * 格式化死因，提取关键字段：reason / queue / exchange / time
     */
    private String formatDeathReason(List<Map<String, ?>> xDeath) {
        if (xDeath == null || xDeath.isEmpty()) {
            return "未知(无x-death header)";
        }
        Map<String, ?> latest = xDeath.get(0);
        return String.format("reason=%s, queue=%s, exchange=%s, time=%s",
                latest.get("reason"),       // "rejected" — 对应 basicNack
                latest.get("queue"),        // 原始来源队列
                latest.get("exchange"),     // 来源交换机
                latest.get("time"));        // 死亡时间
    }
}
