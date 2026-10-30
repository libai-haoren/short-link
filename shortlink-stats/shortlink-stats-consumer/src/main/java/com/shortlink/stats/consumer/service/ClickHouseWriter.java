package com.shortlink.stats.consumer.service;

import com.shortlink.stats.consumer.support.ClickRow;

import java.util.List;

/**
 * ClickHouse 写入出口。
 *
 * <p>与 {@link ClickSink} 的区别：{@code ClickSink} 是"收一行"，这里是"写一批"。
 * 分两层是因为批量策略（多大一批、多久刷一次）和真正怎么写（JDBC / HTTP / Kafka Engine）
 * 是两个独立的变化维度。</p>
 */
public interface ClickHouseWriter {

    /**
     * 同步写入一批。
     *
     * @throws RuntimeException 写入失败
     */
    void write(List<ClickRow> rows);
}
