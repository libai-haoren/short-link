package com.shortlink.redirect.service;

import com.shortlink.redirect.cache.LinkSnapshot;
import com.shortlink.redirect.service.ResolveResult.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LinkResolverTest {

    private static final long NOW = 1_761_350_400_000L;

    private static LinkSnapshot snapshot(String longUrl, int status, long expireTimeMillis) {
        return new LinkSnapshot(longUrl, status, expireTimeMillis, 0L, 1L);
    }

    @Test
    @DisplayName("启用且未过期 → 跳转")
    void redirectsEnabledLink() {
        ResolveResult result = LinkResolver.evaluate(snapshot("https://example.com/a", 1, 0), NOW);

        assertEquals(Outcome.FOUND, result.outcome());
        assertEquals("https://example.com/a", result.location());
    }

    @Test
    @DisplayName("停用 → 403，且优先于过期判定")
    void disabledLinkIsRejected() {
        // 既停用又过期，应返回 DISABLED 而不是 EXPIRED
        ResolveResult result = LinkResolver.evaluate(snapshot("https://example.com", 0, NOW - 1000), NOW);

        assertEquals(Outcome.DISABLED, result.outcome());
        assertNull(result.location());
    }

    @Test
    @DisplayName("未知状态按停用处理（fail-closed）")
    void unknownStatusFailsClosed() {
        assertEquals(Outcome.DISABLED,
                LinkResolver.evaluate(snapshot("https://example.com", 2, 0), NOW).outcome());
        assertEquals(Outcome.DISABLED,
                LinkResolver.evaluate(snapshot("https://example.com", -1, 0), NOW).outcome());
    }

    @Test
    @DisplayName("过期边界：正好等于过期时刻即不可跳转")
    void expiredAtExactMoment() {
        assertEquals(Outcome.EXPIRED,
                LinkResolver.evaluate(snapshot("https://example.com", 1, NOW), NOW).outcome());
    }

    @Test
    @DisplayName("差 1 毫秒过期仍可跳转")
    void notExpiredOneMillisecondBefore() {
        assertEquals(Outcome.FOUND,
                LinkResolver.evaluate(snapshot("https://example.com", 1, NOW + 1), NOW).outcome());
    }

    @Test
    @DisplayName("expireTime=0 表示永不过期")
    void zeroExpireNeverExpires() {
        assertEquals(Outcome.FOUND,
                LinkResolver.evaluate(snapshot("https://example.com", 1, 0), Long.MAX_VALUE / 2).outcome());
    }

    @Test
    @DisplayName("非 http(s) 长链视为数据损坏")
    void nonHttpUrlIsTreatedAsUnavailable() {
        assertEquals(Outcome.UNAVAILABLE,
                LinkResolver.evaluate(snapshot("javascript:alert(1)", 1, 0), NOW).outcome());
        assertEquals(Outcome.UNAVAILABLE,
                LinkResolver.evaluate(snapshot("ftp://example.com", 1, 0), NOW).outcome());
        assertEquals(Outcome.UNAVAILABLE,
                LinkResolver.evaluate(snapshot("", 1, 0), NOW).outcome());
    }

    @Test
    @DisplayName("协议大小写不敏感")
    void schemeIsCaseInsensitive() {
        assertEquals(Outcome.FOUND,
                LinkResolver.evaluate(snapshot("HTTPS://example.com", 1, 0), NOW).outcome());
    }
}
