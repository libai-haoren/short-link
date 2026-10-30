package com.shortlink.stats.consumer.support;

/**
 * 一行待写入 ClickHouse 的点击明细。
 *
 * <p>字段顺序与 {@code sql/03-clickhouse.sql} 的建表语句<b>严格一致</b>：
 * JDBC 批量插入用的是位置参数，顺序错一位就会把 short_code 写进 ip_hash 这种
 * "不报错但数据全废"的灾难。</p>
 *
 * @param eventTime 事件时间（毫秒），写库时转 DateTime64(3)
 * @param gid       业务 ID；事件体里没有，暂时留空（可用 shortCode 反查）
 * @param country   国家，V3 只留字段不填充（需要 IP 库）
 */
public record ClickRow(
        long eventTime,
        String shortCode,
        String gid,
        long ipHash,
        String country,
        String province,
        String city,
        String device,
        String os,
        String browser,
        String referer,
        String userAgent,
        String traceId,
        int isBot
) {

    /** ClickHouse 批量插入的列顺序，单独抽出来给写入端与单测共同引用。 */
    public static final String[] COLUMNS = {
            "event_time", "short_code", "gid", "ip_hash", "country", "province", "city",
            "device", "os", "browser", "referer", "user_agent", "trace_id", "is_bot"
    };

    /** 按 {@link #COLUMNS} 顺序返回值，供 {@code PreparedStatement} 绑定。 */
    public Object[] toParams() {
        return new Object[]{
                eventTime, shortCode, gid, ipHash, country, province, city,
                device, os, browser, referer, userAgent, traceId, isBot
        };
    }
}
