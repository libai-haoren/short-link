package com.shortlink.common.mq;

/**
 * Kafka Topic 常量，与《详细设计》4.6 对应。
 *
 * <p>分区键（message key）的选择比分区数更重要：</p>
 * <ul>
 *   <li>点击流用 {@code shortCode} 做 key —— 同一短链的点击落同一分区，保证局部有序，便于聚合。</li>
 *   <li>缓存同步也用 {@code shortCode} —— 同一短链的变更顺序不能乱。</li>
 * </ul>
 */
public final class Topics {

    /** 点击明细，redirect 生产，stats-consumer 消费。 */
    public static final String CLICK = "sl-click";

    /** 缓存同步事件，admin/Canal 生产，redirect 广播消费。 */
    public static final String CACHE_SYNC = "sl-cache-sync";

    /** 短链变更（binlog 维度），Canal 生产。 */
    public static final String LINK_CHANGE = "sl-link-change";

    /** 点击消费死信。 */
    public static final String CLICK_DLT = "sl-click-dlt";

    private Topics() {
    }
}
