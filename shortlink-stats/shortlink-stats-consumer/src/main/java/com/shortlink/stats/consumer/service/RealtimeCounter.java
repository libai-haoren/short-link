package com.shortlink.stats.consumer.service;

/**
 * 实时计数（Redis）。
 *
 * <p>作用只有一个：让看板在 T+0 就能看到数。真正的口径以 ClickHouse 为准，
 * 所以这里可以接受"丢一两次"和"估算 UV"。</p>
 */
public interface RealtimeCounter {

    /**
     * @param shortCode       短码
     * @param eventTimeMillis 事件时间，用来决定算到哪一天
     * @param ipHash          访客哈希，用于 UV 去重
     */
    void increment(String shortCode, long eventTimeMillis, long ipHash);
}
