package com.shortlink.stats.consumer.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.common.mq.Topics;
import com.shortlink.common.mq.event.ClickEvent;
import com.shortlink.stats.consumer.service.ClickProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Kafka 批量消费入口。
 *
 * <p>用批量而不是逐条：统计场景的吞吐远高于延迟敏感性，
 * 一次 poll 处理 200 条、只提交一次位点，能把提交开销摊薄两个数量级。</p>
 *
 * <p>这一层只做三件事：反序列化、调用处理器、决定要不要提交位点。
 * 所有业务判断都在 {@link ClickProcessor} 里，这样它能脱离 Kafka 单测。</p>
 *
 * <p>位点语义（手动 ack）：</p>
 * <ul>
 *   <li>整批都处理成功 → ack；</li>
 *   <li>有任意一条落库失败 → <b>整批不 ack</b>，位点不推进，重投后重试。
 *       已处理的那些会被去重拦截，不会重复计数；</li>
 *   <li>反序列化失败 → 跳过（毒消息）。不跳过的话这条消息会永远卡住整个分区。</li>
 * </ul>
 */
@Component
public class ClickEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClickEventConsumer.class);

    private final ClickProcessor clickProcessor;
    private final ObjectMapper objectMapper;

    public ClickEventConsumer(ClickProcessor clickProcessor, ObjectMapper objectMapper) {
        this.clickProcessor = clickProcessor;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = Topics.CLICK,
            groupId = "${shortlink.stats.group-id:shortlink-stats}")
    public void onMessage(List<String> payloads, Acknowledgment acknowledgment) {
        boolean allHandled = true;
        for (String payload : payloads) {
            ClickEvent event;
            try {
                event = objectMapper.readValue(payload, ClickEvent.class);
            } catch (Exception ex) {
                // 毒消息：跳过，否则整个分区被一条坏消息堵死
                log.warn("drop unparsable click event: {}", payload, ex);
                continue;
            }
            if (!clickProcessor.handle(event)) {
                allHandled = false;
            }
        }

        if (allHandled) {
            acknowledgment.acknowledge();
        } else {
            // 不 ack：位点不推进，重投后重试。已处理的事件会被去重拦住，不会重复计数
            log.warn("batch partially failed ({} records), offsets not committed", payloads.size());
        }
    }
}
