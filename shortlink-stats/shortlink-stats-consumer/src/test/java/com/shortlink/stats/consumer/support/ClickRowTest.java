package com.shortlink.stats.consumer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列顺序一致性。
 *
 * <p>这组断言看着琐碎，但保护的是最难发现的一类 bug：
 * <b>列顺序错位不会报错，只会让数据静默写错字段</b>（比如把 short_code 写进 ip_hash）。
 * 一次断言就能挡住。</p>
 */
class ClickRowTest {

    private static final ClickRow ROW = new ClickRow(
            1_700_000_000_000L, "abc1234", "01JGID", 42L,
            "", "", "", "PC", "Windows", "Chrome",
            "https://ref", "Mozilla/5.0", "trace-1", 0);

    @Test
    @DisplayName("列数、参数数、SQL 占位符数三者必须一致")
    void countsMatch() {
        assertEquals(14, ClickRow.COLUMNS.length);
        assertEquals(ClickRow.COLUMNS.length, ROW.toParams().length);

        String sql = JdbcClickHouseWriter.buildInsertSql();
        long placeholders = sql.chars().filter(ch -> ch == '?').count();
        assertEquals(ClickRow.COLUMNS.length, placeholders, "占位符数量与列数不一致: " + sql);
    }

    @Test
    @DisplayName("SQL 里的列名顺序与 COLUMNS 完全一致")
    void sqlColumnOrderMatches() {
        String sql = JdbcClickHouseWriter.buildInsertSql();
        String columnPart = sql.substring(sql.indexOf('(') + 1, sql.indexOf(") VALUES"));
        assertEquals(String.join(", ", ClickRow.COLUMNS), columnPart);
    }

    @Test
    @DisplayName("toParams 的下标与列语义对应")
    void toParamsOrder() {
        Object[] params = ROW.toParams();
        assertEquals(1, indexOf("short_code"));
        assertEquals("abc1234", params[indexOf("short_code")]);

        assertEquals("PC", params[indexOf("device")]);
        assertEquals(42L, params[indexOf("ip_hash")]);
        assertEquals(0, params[indexOf("is_bot")]);
        assertEquals(1_700_000_000_000L, params[indexOf("event_time")]);
    }

    private static int indexOf(String column) {
        for (int i = 0; i < ClickRow.COLUMNS.length; i++) {
            if (ClickRow.COLUMNS[i].equals(column)) {
                return i;
            }
        }
        throw new AssertionError("unknown column: " + column);
    }

    @Test
    @DisplayName("表名指向 sql/03-clickhouse.sql 里建的表")
    void tableName() {
        assertTrue(JdbcClickHouseWriter.buildInsertSql().startsWith("INSERT INTO shortlink.link_click_detail"));
    }
}
