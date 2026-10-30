package com.shortlink.stats.api.domain.dto;

import java.time.LocalDate;

/**
 * 实时指标。
 *
 * @param pv 今日累计访问
 * @param uv 今日去重访客（HLL 估算，误差约 0.81%）
 */
public record RealtimeView(String shortCode, LocalDate date, long pv, long uv) {
}
