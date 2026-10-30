package com.shortlink.stats.api.domain.dto;

import com.shortlink.stats.api.support.DailyPoint;

import java.time.LocalDate;
import java.util.List;

/**
 * 短链统计视图。
 *
 * <p><b>为什么不提供 totalUv</b>：UV 是"去重人数"，<b>跨天相加没有意义</b>
 * （同一个人连续三天访问，三天 UV 各 +1，相加得 3 个"人"）。
 * 要算区间真实 UV 必须回到明细做 {@code uniqExact}，那是 ClickHouse 的活。
 * 这里只给能正确计算的 {@code totalPv} 和逐日 UV 的均值/峰值。</p>
 *
 * @param totalPv 区间总访问量（PV 可加）
 * @param avgUv   日均 UV（四舍五入）
 * @param peakUv  单日最高 UV
 */
public record LinkStatsView(
        String shortCode,
        LocalDate from,
        LocalDate to,
        int days,
        long totalPv,
        long avgUv,
        long peakUv,
        List<DailyPoint> points
) {

    public static LinkStatsView of(String shortCode, List<DailyPoint> points, LocalDate from, LocalDate to) {
        long totalPv = 0;
        long sumUv = 0;
        long peakUv = 0;
        for (DailyPoint point : points) {
            totalPv += point.pv();
            sumUv += point.uv();
            peakUv = Math.max(peakUv, point.uv());
        }
        long avgUv = points.isEmpty() ? 0 : Math.round((double) sumUv / points.size());
        return new LinkStatsView(shortCode, from, to, points.size(), totalPv, avgUv, peakUv, points);
    }
}
