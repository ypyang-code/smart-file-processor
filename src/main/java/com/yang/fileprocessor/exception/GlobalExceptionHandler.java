package com.yang.fileprocessor.exception;

import com.yang.fileprocessor.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * <p>
 * 使用 {@code @RestControllerAdvice} 统一拦截 Controller 层抛出的异常，
 * 返回项目统一的 {@link Result} 格式响应，避免 Controller 中重复 try-catch。
 *
 * <h3>不处理的场景</h3>
 * <ul>
 *   <li>MQ Consumer（{@code @RabbitListener}）中的异常 — 由 Spring Retry + MANUAL ACK 管理</li>
 *   <li>非 Controller 层的内部调用 — 由各 Service 自行处理或向上抛</li>
 * </ul>
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 文件上传异常（超出大小限制、Multipart 解析失败等）
     */
    @ExceptionHandler({MultipartException.class, MaxUploadSizeExceededException.class})
    public Result<?> handleMultipartException(MultipartException e) {
        log.warn("文件上传异常: {}", e.getMessage());
        return Result.error("文件上传失败: " + e.getMessage());
    }

    /**
     * 请求参数校验失败（@Valid 校验）
     * <p>当前项目尚未使用 @Valid 注解，此处理器为后续扩展预留。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidationException(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return Result.error("参数校验失败: " + detail);
    }

    /**
     * IO 异常（文件读写失败、磁盘空间不足等）
     */
    @ExceptionHandler(IOException.class)
    public Result<?> handleIOException(IOException e) {
        log.error("IO 异常: {}", e.getMessage(), e);
        return Result.error("文件操作失败: " + e.getMessage());
    }

    /**
     * RabbitMQ 消息队列异常（连接失败、发送失败等）
     */
    @ExceptionHandler(AmqpException.class)
    public Result<?> handleAmqpException(AmqpException e) {
        log.error("消息队列异常: {}", e.getMessage(), e);
        return Result.error("消息队列异常: " + e.getMessage());
    }

    /**
     * 非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        return Result.error("参数错误: " + e.getMessage());
    }

    /**
     * 兜底异常处理器 — 捕获以上所有 handler 未覆盖的异常
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return Result.error("系统异常: " + e.getMessage());
    }
}
