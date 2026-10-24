package com.shortlink.redirect.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LinkSnapshotTest {

    @Test
    @DisplayName("正常解析五个字段")
    void parsesAllFields() {
        LinkSnapshot snapshot = LinkSnapshot.parse(
                List.of("https://example.com/a?x=1", "1", "1761350400000", "0", "1"));

        assertEquals("https://example.com/a?x=1", snapshot.longUrl());
        assertEquals(1, snapshot.status());
        assertEquals(1761350400000L, snapshot.expireTimeMillis());
        assertEquals(0L, snapshot.maxClick());
        assertEquals(1L, snapshot.domainId());
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
