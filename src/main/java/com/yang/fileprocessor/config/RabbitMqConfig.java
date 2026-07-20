package com.yang.fileprocessor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqConfig.class);

    // 业务队列
    public static final String FILE_UPLOAD_QUEUE = "file.upload.queue";

    // Phase 1 闭环 3：死信交换机 / 死信队列
    public static final String FILE_UPLOAD_DLX_EXCHANGE = "file.upload.dlx.exchange";
    public static final String FILE_UPLOAD_DLQ = "file.upload.dlq";
    public static final String FILE_UPLOAD_DLQ_ROUTING_KEY = "file.upload.dlq.routing.key";

    // Phase 1 闭环 4：业务交换机 / routingKey
    public static final String FILE_UPLOAD_EXCHANGE = "file.upload.exchange";
    public static final String FILE_UPLOAD_ROUTING_KEY = "file.upload.routing.key";

    @Bean
    public Queue fileUploadQueue() {
        return QueueBuilder.durable(FILE_UPLOAD_QUEUE)
                .deadLetterExchange(FILE_UPLOAD_DLX_EXCHANGE)
                .deadLetterRoutingKey(FILE_UPLOAD_DLQ_ROUTING_KEY)
                .build();
    }

    // Phase 1 闭环 3：死信交换机（DirectExchange, durable）
    @Bean
    public DirectExchange fileUploadDlxExchange() {
        return new DirectExchange(FILE_UPLOAD_DLX_EXCHANGE, true, false);
    }

    // Phase 1 闭环 3：死信队列（durable）
    @Bean
    public Queue fileUploadDlq() {
        return new Queue(FILE_UPLOAD_DLQ, true);
    }

    // Phase 1 闭环 3：死信绑定（DLX ← DLQ）
    @Bean
    public Binding fileUploadDlqBinding() {
        return BindingBuilder.bind(fileUploadDlq())
                .to(fileUploadDlxExchange())
                .with(FILE_UPLOAD_DLQ_ROUTING_KEY);
    }

    // Phase 1 闭环 4：业务交换机（DirectExchange, durable）
    @Bean
    public DirectExchange fileUploadExchange() {
        return new DirectExchange(FILE_UPLOAD_EXCHANGE, true, false);
    }

    // Phase 1 闭环 4：业务队列绑定到业务交换机
    @Bean
    public Binding fileUploadBinding() {
        return BindingBuilder.bind(fileUploadQueue())
                .to(fileUploadExchange())
                .with(FILE_UPLOAD_ROUTING_KEY);
    }

    // JSON 消息转换器（让 RabbitMQ 能传输自定义对象）
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // RabbitTemplate：JSON 转换器 + Producer Confirm + Return
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter());

        // Phase 1 闭环 4：Producer Confirm — 确认消息到达 Broker
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String id = correlationData != null ? correlationData.getId() : "null";
            if (ack) {
                log.info("消息已被Broker确认接收：correlationId={}", id);
            } else {
                log.error("消息未被Broker确认接收：correlationId={}, cause={}",
                          id, cause != null ? cause : "未知原因");
            }
        });

        // Phase 1 闭环 4：ReturnsCallback — 捕获无法路由的消息
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(returned -> {
            String correlationId = returned.getMessage()
                    .getMessageProperties().getCorrelationId();
            log.error("消息无法路由：replyCode={}, replyText={}, exchange={}, " +
                      "routingKey={}, correlationId={}, deliveryMode={}, bodyLength={}",
                      returned.getReplyCode(), returned.getReplyText(),
                      returned.getExchange(), returned.getRoutingKey(),
                      correlationId != null ? correlationId : "null",
                      returned.getMessage().getMessageProperties().getDeliveryMode(),
                      returned.getMessage().getBody().length);
        });

        return rabbitTemplate;
    }
}