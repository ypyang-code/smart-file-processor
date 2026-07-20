package com.yang.fileprocessor.service;

import com.rabbitmq.client.Channel;
import com.yang.fileprocessor.config.RabbitMqConfig;
import com.yang.fileprocessor.dto.FileUploadMessage;
import com.yang.fileprocessor.enums.ParseStatusEnum;
import com.yang.fileprocessor.utils.FileTypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 文件上传消费者（手动 ACK + Spring Retry）
 *
 * <h3>ACK/NACK/Retry 策略</h3>
 * <ul>
 *   <li><b>成功</b>：4 步处理全部完成后调 {@code channel.basicAck()}
 *       （① 标记 PARSING、② OSS 上传、③ 提取文本、
 *         ④ 事务写 MySQL + outbox_event，ES 异步同步）</li>
 *   <li><b>不可重试异常</b>（{@link FileNotFoundException}）：
 *       调 {@code channel.basicNack(requeue=false)}，消息丢弃，DB 记录失败</li>
 *   <li><b>可重试异常</b>（OSS/DB/IO 临时故障）：
 *       不调 ACK/NACK，向上抛异常，由 Spring Retry 接管</li>
 * </ul>
 *
 * <h3>重试策略</h3>
 * <p>最多尝试 3 次（首次消费 1 次 + 失败后重试 2 次），退避间隔 2s → 4s。
 * 3 次全部失败后，Container 根据 {@code default-requeue-rejected=false}
 * 执行 {@code basicNack(requeue=false)}。</p>
 *
 * <h3>过渡风险（闭环 2）</h3>
 * <p>当前未配置 DLX/DLQ。{@code requeue=false} 的消息会被 RabbitMQ 直接丢弃。
 * 但 DB 中已写入 parse_status=PARSE_FAILED + 错误原因，日志保留 fileId 和异常详情。
 * 闭环 3 接入 DLX/DLQ 后，这些消息将被自动路由到死信队列。</p>
 */
@Service
public class FileUploadConsumer {

    private static final Logger log = LoggerFactory.getLogger(FileUploadConsumer.class);

    @Autowired
    private OssService ossService;

    @Autowired
    private FileInfoService fileInfoService;

    @Autowired
    private FileContentExtractor contentExtractor;

    @RabbitListener(queues = RabbitMqConfig.FILE_UPLOAD_QUEUE)
    public void handleFileUpload(
            FileUploadMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {

        Long fileId = message.getFileId();
        String fileName = message.getFileName();
        String filePath = message.getFilePath();

        log.info("开始处理文件上传：fileId={}, fileName={}, deliveryTag={}",
                 fileId, fileName, deliveryTag);

        try {
            // 0. 标记解析中
            fileInfoService.updateParseStatus(fileId, ParseStatusEnum.PARSING);

            // 1. 上传到 OSS
            InputStream inputStream = new FileInputStream(filePath);
            String ossFileName = "files/" + fileId + "_" + fileName;
            String ossUrl = ossService.uploadFile(ossFileName, inputStream);
            inputStream.close();
            log.info("OSS上传完成：fileId={}, ossUrl={}", fileId, ossUrl);

            // 2. 提取文件文字内容
            String fileType = FileTypeUtil.getFileType(fileName);
            String content = contentExtractor.extractContent(filePath, fileType);
            log.info("内容提取完成：fileId={}, 内容长度={}", fileId, content.length());

            // 3. 事务更新 MySQL + 写入 outbox 事件（ES 异步同步）
            //    同一事务内：file_info status=2 + outbox_event PENDING
            //    事务成功后 ACK，ES 同步由 OutboxSyncScheduler 负责
            fileInfoService.updateStatusWithOutbox(fileId, 2, content, ossUrl);

            // 4. 删除临时文件
            java.io.File tempFile = new java.io.File(filePath);
            if (tempFile.exists()) {
                tempFile.delete();
            }

            log.info("文件处理完成：fileId={}", fileId);

            // 5. 手动 ACK
            // basicAck IOException 内部 catch，不向外抛：
            // 业务已全部成功（DB status=2, outbox 事件已提交），
            // 即使 ACK 失败导致消息重新投递，重复处理也是幂等的
            try {
                channel.basicAck(deliveryTag, false);
                log.info("文件处理成功并确认：fileId={}, deliveryTag={}",
                         fileId, deliveryTag);
            } catch (IOException ackEx) {
                log.error("basicAck IO异常(业务已成功完成)，消息保持unacked，" +
                          "将在Channel关闭后重新投递：fileId={}, deliveryTag={}",
                          fileId, deliveryTag, ackEx);
            }

        } catch (FileNotFoundException e) {
            // 不可重试异常：文件不存在，重试多少次都没用
            log.error("文件不存在，丢弃消息：fileId={}, fileName={}, filePath={}",
                      fileId, fileName, filePath, e);
            fileInfoService.updateStatus(fileId, 3,
                    "文件不存在: " + e.getMessage(), null);

            // basicNack IOException 内部 catch，不向外抛：
            // DB 已记录失败状态，即使 Nack 失败导致消息重新投递，
            // 下次进入仍会 FileNotFoundException → 再次尝试 Nack
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException nackEx) {
                log.error("basicNack IO异常(DB已记录失败)，消息保持unacked：" +
                          "fileId={}, deliveryTag={}", fileId, deliveryTag, nackEx);
            }

        } catch (Exception e) {
            // 可重试异常：OSS 超时 / DB 连接断开 / IO 异常
            log.error("文件处理失败，将触发Spring Retry重试：" +
                      "fileId={}, fileName={}, 异常类型={}",
                      fileId, fileName, e.getClass().getSimpleName(), e);
            fileInfoService.updateStatus(fileId, 3,
                    "处理失败(将重试): " + e.getClass().getSimpleName()
                    + " - " + e.getMessage(), null);

            // ★ 不调 basicAck / basicNack，向上抛异常
            // → Spring Retry 拦截 → 按配置重试（2s → 4s，最多尝试 3 次）
            // → 3 次全部失败 → Container + default-requeue-rejected=false
            //   → Container 调用 basicNack(requeue=false)
            throw new RuntimeException("文件处理失败，触发Spring Retry重试", e);
        }
    }

}
