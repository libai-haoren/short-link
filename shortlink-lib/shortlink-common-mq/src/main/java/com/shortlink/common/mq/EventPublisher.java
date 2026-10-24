package com.shortlink.common.mq;

/**
 * 事件发布抽象。
 *
 * <p>抽象成接口而不是直接注入 {@code KafkaTemplate}，有两个实际好处：</p>
 * <ol>
 *   <li>redirect 单测时可以用内存实现，不需要起 Kafka；</li>
 *   <li>后续换 RocketMQ 只改实现，不动业务代码。</li>
 * </ol>
 */
public interface EventPublisher {

    /**
     * 异步发布。
     *
     * @param topic topic 名，见 {@link Topics}
     * @param key   分区键，同 key 有序
     * @param event 事件体，实现方负责序列化
     */
    void publish(String topic, String key, Object event);
}
