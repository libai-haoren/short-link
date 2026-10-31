package com.shortlink.redirect.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.shortlink.redirect.metrics.RedirectMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * L1 失效语义。
 *
 * <p>这是 V2 的核心：失效必须真的把本地副本清掉，否则改链还是要等 10 分钟 TTL。
 * 用计数桩统计回源次数，比断言"Caffeine 里还有没有"更贴近真实行为。</p>
 *
 * <p>另外锁住两个回填保护：读取期间被失效的不再回填、低版本不覆盖高版本。</p>
 */
class TwoLevelLinkCacheTest {

    private static final String CODE = "aB3xK9z";

    private CountingCache delegate;
    private TwoLevelLinkCache cache;

    @BeforeEach
    void setUp() {
        Cache<String, LinkSnapshot> local = Caffeine.newBuilder().maximumSize(100).build();
        delegate = new CountingCache();
        cache = new TwoLevelLinkCache(local, delegate, new RedirectMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("第二次读取命中 L1，不再回源")
    void secondGetHitsLocalCache() {
        cache.get(CODE).block();
        cache.get(CODE).block();
        assertEquals(1, delegate.calls.get());
    }

    @Test
    @DisplayName("invalidate 之后必须回源——这正是'改链立即生效'的基础")
    void invalidateForcesReload() {
        cache.get(CODE).block();
        cache.invalidate(CODE);
        cache.get(CODE).block();
        assertEquals(2, delegate.calls.get());
    }

    @Test
    @DisplayName("invalidateAll 清空全部——版本号兜底与重建后使用")
    void invalidateAllClearsEverything() {
        cache.get("aaaaaaa").block();
        cache.get("bbbbbbb").block();
        assertEquals(2, delegate.calls.get());

        cache.invalidateAll();
        cache.get("aaaaaaa").block();
        cache.get("bbbbbbb").block();
        assertEquals(4, delegate.calls.get());
    }

    @Test
    @DisplayName("失效一个短码不影响其他短码")
    void invalidateIsPerKey() {
        cache.get("aaaaaaa").block();
        cache.get("bbbbbbb").block();
        cache.invalidate("aaaaaaa");

        cache.get("bbbbbbb").block();
        assertEquals(2, delegate.calls.get());
    }

    @Test
    @DisplayName("并发回填时，低版本不会覆盖高版本")
    void concurrentRefillKeepsHigherVersion() {
        delegate.nextVersion = 5L;
        Mono<LinkSnapshot> newer = cache.get(CODE);
        delegate.nextVersion = 3L;
        Mono<LinkSnapshot> older = cache.get(CODE);

        // 低版本先落地，高版本后落地
        older.block();
        newer.block();

        assertEquals(5L, cache.get(CODE).block().version(), "L1 应保留高版本快照");
        assertEquals(2, delegate.calls.get(), "第三次读取应命中 L1，不再回源");
    }

    @Test
    @DisplayName("读取期间被失效：旧值不回填，下一次仍然回源")
    void staleRefillIsDiscardedAfterInvalidation() {
        delegate.onGet = () -> cache.invalidate(CODE);

        cache.get(CODE).block();

        assertEquals(0, cache.estimatedSize(), "读取期间发生的失效应阻止旧值回填");
        delegate.onGet = null;
        cache.get(CODE).block();
        assertEquals(2, delegate.calls.get(), "L1 为空，必须重新回源");
    }

    /** 假的 L2：可指定返回版本、可在 get 里插入副作用（模拟失效消息先到）。 */
    private static final class CountingCache implements LinkCache {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile long nextVersion = 0L;
        private volatile Runnable onGet;

        @Override
        public Mono<LinkSnapshot> get(String shortCode) {
            calls.incrementAndGet();
            Runnable sideEffect = onGet;
            if (sideEffect != null) {
                sideEffect.run();
            }
            return Mono.just(new LinkSnapshot(
                    "https://example.com/" + shortCode, 1, 0L, 0L, 1L, nextVersion));
        }

        @Override
        public void invalidate(String shortCode) {
            // 不关心
        }

        @Override
        public void invalidateAll() {
            // 不关心
        }
    }
}
