package com.shortlink.stats.api.support;

import com.shortlink.common.core.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatsRangeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @Test
    @DisplayName("参数全空时默认最近 7 天")
    void defaultsToSevenDays() {
        StatsRange range = StatsRange.of(null, null, TODAY);

        assertEquals(TODAY.minusDays(6), range.from());
        assertEquals(TODAY, range.to());
        assertEquals(7, range.days());
    }

    @Test
    @DisplayName("只给 from 时，to 补成今天")
    void fromOnly() {
        StatsRange range = StatsRange.of(TODAY.minusDays(2), null, TODAY);

        assertEquals(TODAY.minusDays(2), range.from());
        assertEquals(TODAY, range.to());
        assertEquals(3, range.days());
    }

    @Test
    @DisplayName("from 晚于 to 必须报错，不能静默返回空图")
    void fromAfterTo() {
        assertThrows(BizException.class,
                () -> StatsRange.of(TODAY, TODAY.minusDays(1), TODAY));
    }

    @Test
    @DisplayName("to 在未来必须报错，否则图表 x 轴出现未来的空点")
    void toInFuture() {
        assertThrows(BizException.class,
                () -> StatsRange.of(TODAY.minusDays(1), TODAY.plusDays(1), TODAY));
    }

    @Test
    @DisplayName("超过 90 天拒绝，正好 90 天放行")
    void maxDays() {
        StatsRange ok = StatsRange.of(TODAY.minusDays(89), TODAY, TODAY);
        assertEquals(90, ok.days());

        assertThrows(BizException.class,
                () -> StatsRange.of(TODAY.minusDays(90), TODAY, TODAY));
    }

    @Test
    @DisplayName("单日查询允许")
    void singleDay() {
        StatsRange range = StatsRange.of(TODAY, TODAY, TODAY);
        assertEquals(1, range.days());
    }
}
