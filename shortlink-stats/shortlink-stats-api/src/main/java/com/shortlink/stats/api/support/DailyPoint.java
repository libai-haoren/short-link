package com.shortlink.stats.api.support;

import java.time.LocalDate;

/**
 * 日趋势上的一个点。
 *
 * @param source 数据来源：{@code DB} 已聚合、{@code REALTIME} 来自 Redis 实时、{@code EMPTY} 无数据
 */
public record DailyPoint(LocalDate date, long pv, long uv, long uvExact, String source) {
}
