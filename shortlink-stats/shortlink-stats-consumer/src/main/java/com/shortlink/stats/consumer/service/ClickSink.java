package com.shortlink.stats.consumer.service;

import com.shortlink.stats.consumer.support.ClickRow;

/**
 * 明细落库出口。
 *
 * <p>抽成接口的两个理由：单测时用内存实现；将来换 Kafka→ClickHouse 的
 * Kafka Engine 表或直接走 HTTP 接口时，处理逻辑不用改。</p>
 */
public interface ClickSink {

    /**
     * 接收一行明细。实现方负责批量与重试。
     *
     * @throws RuntimeException 表示落库失败，调用方据此决定是否提交位点
     */
    void accept(ClickRow row);
}
