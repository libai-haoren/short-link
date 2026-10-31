package com.shortlink.redirect.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LinkSnapshotTest {

    @Test
    @DisplayName("正常解析六个字段")
    void parsesAllFields() {
        LinkSnapshot snapshot = LinkSnapshot.parse(
                List.of("https://example.com/a?x=1", "1", "1761350400000", "0", "1", "7"));

        assertEquals("https://example.com/a?x=1", snapshot.longUrl());
        assertEquals(1, snapshot.status());
        assertEquals(1761350400000L, snapshot.expireTimeMillis());
        assertEquals(0L, snapshot.maxClick());
        assertEquals(1L, snapshot.domainId());
        assertEquals(7L, snapshot.version());
    }

    @Test
    @DisplayName("历史数据没有 version 字段：按 0 处理，而不是报数据损坏")
    void missingVersionFallsBackToZero() {
        // 版本是后加的字段，加之前写入的缓存只有五个字段。若这里抛异常，
        // 上线那一刻所有老缓存都会变成 503
        LinkSnapshot snapshot = LinkSnapshot.parse(
                List.of("https://example.com", "1", "0", "0", "1"));

        assertEquals(0L, snapshot.version());
    }

    @Test
    @DisplayName("version 不可解析时按 0 处理：它只用于排序，不值得让整条链路报损坏")
    void unparsableVersionFallsBackToZero() {
        assertEquals(0L, LinkSnapshot.parse(
                List.of("https://example.com", "1", "0", "0", "1", "abc")).version());
    }

    @Test
    @DisplayName("expireTime=0 表示永久")
    void zeroExpireMeansNeverExpire() {
        assertEquals(0L, LinkSnapshot.parse(
                List.of("https://example.com", "1", "0", "0", "1")).expireTimeMillis());
    }

    @Test
    @DisplayName("字段数不足必须报损坏，而不是猜默认值")
    void rejectsTooFewFields() {
        assertThrows(CorruptCacheDataException.class, () -> LinkSnapshot.parse(List.of("https://a.com")));
        // 前五个字段缺任何一个都是真损坏；只有第六个（version）允许缺省
        assertThrows(CorruptCacheDataException.class, () -> LinkSnapshot.parse(
                List.of("https://a.com", "1", "0", "0")));
        assertThrows(CorruptCacheDataException.class, () -> LinkSnapshot.parse(List.of()));
        assertThrows(CorruptCacheDataException.class, () -> LinkSnapshot.parse(null));
    }

    @Test
    @DisplayName("longUrl 缺失或空白必须报损坏")
    void rejectsMissingLongUrl() {
        assertThrows(CorruptCacheDataException.class, () -> LinkSnapshot.parse(
                Arrays.asList(null, "1", "0", "0", "1")));
        assertThrows(CorruptCacheDataException.class, () -> LinkSnapshot.parse(
                List.of("   ", "1", "0", "0", "1")));
    }

    @Test
    @DisplayName("数字字段非法必须报损坏")
    void rejectsNonNumericFields() {
        assertThrows(CorruptCacheDataException.class, () -> LinkSnapshot.parse(
                List.of("https://a.com", "abc", "0", "0", "1")));
        assertThrows(CorruptCacheDataException.class, () -> LinkSnapshot.parse(
                List.of("https://a.com", "1", "not-a-number", "0", "1")));
        assertThrows(CorruptCacheDataException.class, () -> LinkSnapshot.parse(
                List.of("https://a.com", "1", "0", "x", "1")));
    }

    @Test
    @DisplayName("字段值允许有前后空格（Redis 里可能被人手工改过）")
    void trimsNumericFields() {
        LinkSnapshot snapshot = LinkSnapshot.parse(
                List.of("https://example.com", " 1 ", " 0 ", " 0 ", " 1 "));
        assertEquals(1, snapshot.status());
        assertEquals(1L, snapshot.domainId());
    }
}
