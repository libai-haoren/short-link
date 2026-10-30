package com.shortlink.common.mq;

/**
 * Kafka Topic 常量，与《详细设计》4.6 对应。
 *
 * <p>分区键（message key）的选择比分区数更重要：点击流用 {@code shortCode} 做 key，
 * 保证同一短链的点击落同一分区，局部有序，便于聚合。</p>
 *
 * <p><b>注意</b>：缓存失效<b>不</b>走 Kafka。它是"通知"而不是"数据流"，
 * 用 Redis Pub/Sub 更合适（详见 {@code com.shortlink.common.cache.CacheChannels}）。
 * Kafka 只承载需要分区有序与回放能力的点击流。</p>
 */
public final class Topics {

    /** 点击明细，redirect 生产，stats-consumer 消费。 */
    public static final String CLICK = "sl-click";

    /** 短链变更（binlog 维度），Canal 生产。 */
    public static final String LINK_CHANGE = "sl-link-change";

    /** 点击消费死信。 */
    public static final String CLICK_DLT = "sl-click-dlt";

    private Topics() {
    }
}
