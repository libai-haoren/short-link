package com.shortlink.common.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * MQ 自动装配。
 *
 * <p>只在 {@code KafkaTemplate} 存在时才注册发布器：
 * 没有 Kafka 的环境（本地只跑 admin + redirect）不应该因为缺一个 bean 起不来。
 * 业务代码注入的是 {@link EventPublisher} 接口，缺实现时自己决定要不要降级。</p>
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
public class MqAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    @ConditionalOnBean(KafkaTemplate.class)
    public EventPublisher eventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                         ObjectMapper objectMapper) {
        return new KafkaEventPublisher(kafkaTemplate, objectMapper);
    }
}
