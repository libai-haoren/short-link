package com.shortlink.stats.api.support;

import com.shortlink.stats.api.domain.entity.StatsLinkDailyDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailySeriesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    private static StatsLinkDailyDO row(LocalDate date, long pv, long uv) {
        StatsLinkDailyDO row = new StatsLinkDailyDO();
        row.setStatDate(date);
        row.setPv(pv);
        row.setUv(uv);
        row.setUvExact(0L);
        return row;
    }

    @Test
    @DisplayName("没有数据的日子补 0 点，保证曲线连续")
    void fillsGaps() {
        StatsRange range = StatsRange.of(TODAY.minusDays(3), TODAY, TODAY);
        List<StatsLinkDailyDO> rows = List.of(row(TODAY.minusDays(3), 10, 5));

        List<DailyPoint> points = DailySeries.fill(rows, range, TODAY, Map.of());

        assertEquals(4, points.size(), "3 天区间应该有 4 个点");
        assertEquals(10, points.get(0).pv());
        assertEquals(0, points.get(1).pv());
        assertEquals(DailySeries.SOURCE_EMPTY, points.get(1).source());
        assertEquals(DailySeries.SOURCE_DB, points.get(0).source());
    }

    @Test
    @DisplayName("今天没有聚合行时，用 Redis 实时值")
    void realtimeOverridesToday() {
        StatsRange range = StatsRange.of(TODAY, TODAY, TODAY);
        Map<LocalDate, RealtimePoint> realtime = Map.of(TODAY, new RealtimePoint(88, 12));

        List<DailyPoint> points = DailySeries.fill(List.of(), range, TODAY, realtime);

        assertEquals(88, points.get(0).pv());
        assertEquals(12, points.get(0).uv());
        assertEquals(DailySeries.SOURCE_REALTIME, points.get(0).source());
    }

    @Test
    @DisplayName("实时值小于已聚合值时不覆盖，避免曲线倒退")
    void keepsLargerValue() {
        StatsRange range = StatsRange.of(TODAY, TODAY, TODAY);
        List<StatsLinkDailyDO> rows = List.of(row(TODAY, 100, 30));
        Map<LocalDate, RealtimePoint> realtime = Map.of(TODAY, new RealtimePoint(80, 20));

        List<DailyPoint> points = DailySeries.fill(rows, range, TODAY, realtime);

        assertEquals(100, points.get(0).pv(), "已聚合 100，实时 80，应该保留 100");
        assertEquals(30, points.get(0).uv());
        assertEquals(DailySeries.SOURCE_DB, points.get(0).source());
    }

    @Test
    @DisplayName("实时值更大时覆盖（Redis 比聚合新）")
    void realtimeWinsWhenLarger() {
        StatsRange range = StatsRange.of(TODAY, TODAY, TODAY);
        List<StatsLinkDailyDO> rows = List.of(row(TODAY, 50, 10));
        Map<LocalDate, RealtimePoint> realtime = Map.of(TODAY, new RealtimePoint(120, 40));

        List<DailyPoint> points = DailySeries.fill(rows, range, TODAY, realtime);

        assertEquals(120, points.get(0).pv());
        assertEquals(40, points.get(0).uv());
        assertEquals(DailySeries.SOURCE_REALTIME, points.get(0).source());
    }

    @Test
    @DisplayName("区间外的行不会混进结果")
    void ignoresRowsOutsideRange() {
        StatsRange range = StatsRange.of(TODAY, TODAY, TODAY);
        List<StatsLinkDailyDO> rows = List.of(
                row(TODAY.minusDays(10), 999, 999),
                row(TODAY, 5, 2));

        List<DailyPoint> points = DailySeries.fill(rows, range, TODAY, Map.of());

        assertEquals(1, points.size());
        assertEquals(5, points.get(0).pv());
    }

    @Test
    @DisplayName("null pv/uv 归一成 0，不抛 NPE")
    void handlesNullCounters() {
        StatsRange range = StatsRange.of(TODAY, TODAY, TODAY);
        StatsLinkDailyDO row = new StatsLinkDailyDO();
        row.setStatDate(TODAY);
        row.setPv(null);
        row.setUv(null);

        List<DailyPoint> points = DailySeries.fill(List.of(row), range, TODAY, Map.of());

        assertEquals(0, points.get(0).pv());
        assertEquals(0, points.get(0).uv());
    }
}
