package com.shortlink.stats.consumer.service;

/**
 * 消费端去重。
 *
 * <p>Kafka 是"至少一次"投递：消费者处理完但没来得及提交位点就重启，消息会重投。
 * 不去重的话 PV 会系统性偏高——而且偏高多少完全取决于抖动，事后对不上账。</p>
 */
public interface DedupGuard {

    /**
     * @return true 表示第一次见到这个 eventId
     */
    boolean firstSeen(String eventId);

    /**
     * 撤销去重标记。
     *
     * <p>落库失败时必须调用：否则重投的消息会被当成"重复"直接跳过，
     * 结果就是<b>悄悄丢数据</b>——比报错难查得多。</p>
     */
    default void forget(String eventId) {
    }
}
