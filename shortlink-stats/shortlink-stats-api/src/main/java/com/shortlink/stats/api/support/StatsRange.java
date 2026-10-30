package com.shortlink.stats.api.support;

import com.shortlink.common.core.exception.BizException;
import com.shortlink.common.core.result.ErrorCode;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 查询区间校验。
 *
 * <p>三个必须挡住的情况，任何一个漏了都会变成慢查询或空结果：</p>
 * <ol>
 *   <li>{@code from > to} —— 返回空数据而不是报错，用户会以为"没数据"；</li>
 *   <li>{@code to} 在未来 —— 图表 x 轴出现未来的空位，看起来像丢数；</li>
 *   <li>区间过大 —— 直接扫全表。90 天是报表的合理上限。</li>
 * </ol>
 */
public record StatsRange(LocalDate from, LocalDate to) {

    public static final int MAX_DAYS = 90;
    public static final int DEFAULT_DAYS = 7;

    /**
     * @param from  为空时默认取 to 前 6 天（共 7 天）
     * @param to    为空时默认取今天
     * @param today 显式传入而不是内部取，才能测边界
     */
    public static StatsRange of(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1) : from;

        if (start.isAfter(end)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "from 不能晚于 to");
        }
        if (end.isAfter(today)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "to 不能晚于今天");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_DAYS) {
            throw new BizException(ErrorCode.PARAM_INVALID, "查询区间不能超过 " + MAX_DAYS + " 天");
        }
        return new StatsRange(start, end);
    }

    public int days() {
        return (int) (ChronoUnit.DAYS.between(from, to) + 1);
    }
}
