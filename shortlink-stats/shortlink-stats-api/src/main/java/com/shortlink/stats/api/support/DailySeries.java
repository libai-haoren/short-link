package com.shortlink.stats.api.support;

import com.shortlink.stats.api.domain.entity.StatsLinkDailyDO;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把"库里的行 + Redis 的实时值"拼成连续曲线。
 *
 * <p>纯函数，不碰 IO，所以能穷举边界。三件事必须做对：</p>
 * <ol>
 *   <li><b>补齐空洞</b>：没有数据的那天也要有点，否则前端画出来的折线会把 10-01 和 10-05 连成一条直线；</li>
 *   <li><b>今天用实时值覆盖</b>：日聚合是 T+1 的，今天的行还不存在；</li>
 *   <li><b>取较大值而不是覆盖</b>：如果今天已经部分聚合过（pv=100），而实时显示 80，
 *       覆盖会让图表"倒退"。用 max 保证单调不减。</li>
 * </ol>
 */
public final class DailySeries {

    public static final String SOURCE_DB = "DB";
    public static final String SOURCE_REALTIME = "REALTIME";
    public static final String SOURCE_EMPTY = "EMPTY";

    private DailySeries() {
    }

    public static List<DailyPoint> fill(List<StatsLinkDailyDO> rows,
                                        StatsRange range,
                                        LocalDate today,
                                        Map<LocalDate, RealtimePoint> realtime) {
        Map<LocalDate, StatsLinkDailyDO> byDate = new HashMap<>();
        if (rows != null) {
            for (StatsLinkDailyDO row : rows) {
                if (row.getStatDate() != null) {
                    byDate.put(row.getStatDate(), row);
                }
            }
        }

        List<DailyPoint> points = new ArrayList<>(range.days());
        for (LocalDate date = range.from(); !date.isAfter(range.to()); date = date.plusDays(1)) {
            StatsLinkDailyDO row = byDate.get(date);
            long pv = row == null ? 0L : nullToZero(row.getPv());
            long uv = row == null ? 0L : nullToZero(row.getUv());
            long uvExact = row == null ? 0L : nullToZero(row.getUvExact());
            String source = row == null ? SOURCE_EMPTY : SOURCE_DB;

            RealtimePoint live = realtime == null ? null : realtime.get(date);
            if (live != null) {
                if (live.pv() > pv) {
                    pv = live.pv();
                    source = SOURCE_REALTIME;
                }
                uv = Math.max(uv, live.uv());
            }

            // 防御：区间校验已经挡住了未来日期，这里再兜一次，绝不返回未来的"数据"
            if (date.isAfter(today)) {
                pv = 0L;
                uv = 0L;
                uvExact = 0L;
                source = SOURCE_EMPTY;
            }
            points.add(new DailyPoint(date, pv, uv, uvExact, source));
        }
        return points;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
