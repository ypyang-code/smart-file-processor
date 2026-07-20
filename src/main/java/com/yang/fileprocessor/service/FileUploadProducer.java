package com.yang.fileprocessor.service;

import com.yang.fileprocessor.config.RabbitMqConfig;
import com.yang.fileprocessor.dto.FileUploadMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 消息生产者（Producer Confirm + 消息持久化）
 *
 * <p>把文件上传任务发送到业务交换机 {@code file.upload.exchange}，
 * 附带 CorrelationData 用于 Producer Confirm，并设置 deliveryMode=PERSISTENT
 * 确保 Broker 重启后消息不丢失。
 */
@Service
public class FileUploadProducer {

    private static final Logger log = LoggerFactory.getLogger(FileUploadProducer.class);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送文件上传任务（Producer Confirm + 消息持久化）
     *
     * <p>convertAndSend 可能抛出 {@link AmqpException}（网络断开/Broker 不可用）。
     * 异常在此处记录后继续向上抛，由调用方（FileController / ChunkUploadService）
     * 沿用现有失败处理逻辑。
     */
    public void sendUploadTask(FileUploadMessage message) {
        String correlationId = "file-upload:" + message.getFileId()
                + ":" + UUID.randomUUID().toString().substring(0, 8);
        CorrelationData correlationData = new CorrelationData(correlationId);

        MessagePostProcessor postProcessor = msg -> {
            msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return msg;
        };

        log.info("发送文件上传任务：fileId={}, fileName={}, correlationId={}, exchange={}, routingKey={}",
                 message.getFileId(), message.getFileName(), correlationId,
                 RabbitMqConfig.FILE_UPLOAD_EXCHANGE, RabbitMqConfig.FILE_UPLOAD_ROUTING_KEY);

        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.FILE_UPLOAD_EXCHANGE,
                    RabbitMqConfig.FILE_UPLOAD_ROUTING_KEY,
                    message,
                    postProcessor,
                    correlationData);
        } catch (AmqpException e) {
            log.error("消息发送异常：fileId={}, correlationId={}, exchange={}, routingKey={}",
                      message.getFileId(), correlationId,
                      RabbitMqConfig.FILE_UPLOAD_EXCHANGE,
                      RabbitMqConfig.FILE_UPLOAD_ROUTING_KEY, e);
            throw e;
        }
    }
}
