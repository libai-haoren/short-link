package com.shortlink.common.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 基于 Kafka 的事件发布器。
 *
 * <p><b>永不抛异常</b>：发布失败只记日志。调用方是跳转热路径，
 * "统计少一条"永远比"用户跳不过去"更可接受。</p>
 *
 * <p>序列化用手动 JSON + {@code StringSerializer}，而不是让 Kafka 配 JsonSerializer：
 * 这样事件体的格式只由这一个类决定，改字段不会因为序列化器配置不同而出现
 * "生产者发出去的是 A 格式、消费者按 B 格式解"。</p>
 */
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String topic, String key, Object event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            log.warn("serialize event failed, topic={}, type={}", topic, event.getClass().getSimpleName(), ex);
            return;
        }
        try {
            // 异步发送；回调里只记日志，绝不把异常抛回调用方
            kafkaTemplate.send(topic, key, payload).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("publish event failed, topic={}, key={}, reason={}", topic, key, ex.toString());
                } else if (log.isDebugEnabled()) {
                    log.debug("published, topic={}, key={}, offset={}",
                            topic, key, result.getRecordMetadata().offset());
                }
            });
        } catch (RuntimeException ex) {
            // 例如 Kafka 不可用导致 max.block.ms 超时
            log.warn("publish event rejected, topic={}, key={}, reason={}", topic, key, ex.toString());
        }
    }
}
