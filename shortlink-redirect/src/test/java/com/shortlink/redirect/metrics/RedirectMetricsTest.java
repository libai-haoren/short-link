package com.shortlink.redirect.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 失效来源计数。
 *
 * <p>这组用例锁的是一个真实的 bug：早先用 {@code boolean fromVersionPoll} 传来源，
 * 重建后的全清消息被记成了"版本号兜底"，导致看板上兜底次数虚高，
 * 反而掩盖了真正需要发现的 Pub/Sub 丢消息。</p>
 */
class RedirectMetricsTest {

    @Test
    @DisplayName("EVICT_ALL 单独计数，不能算到 version 兜底头上")
    void evictAllIsCountedSeparatelyFromVersionFallback() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedirectMetrics metrics = new RedirectMetrics(registry);

        metrics.cacheEvicted(RedirectMetrics.EvictSource.EVICT_ALL);

        assertEquals(1.0, counter(registry, "evict_all").count());
        assertEquals(0.0, counter(registry, "version").count());
        assertEquals(0.0, counter(registry, "pubsub").count());
    }

    @Test
    @DisplayName("三种来源各自独立计数")
    void evictSourcesAreIndependent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedirectMetrics metrics = new RedirectMetrics(registry);

        metrics.cacheEvicted(RedirectMetrics.EvictSource.PUBSUB);
        metrics.cacheEvicted(RedirectMetrics.EvictSource.PUBSUB);
        metrics.cacheEvicted(RedirectMetrics.EvictSource.VERSION);

        assertEquals(2.0, counter(registry, "pubsub").count());
        assertEquals(1.0, counter(registry, "version").count());
        assertEquals(0.0, counter(registry, "evict_all").count());
    }

    private static Counter counter(SimpleMeterRegistry registry, String source) {
        Counter counter = registry.get("shortlink.cache.evict.total").tag("source", source).counter();
        assertNotNull(counter, "counter not registered for source=" + source);
        return counter;
    }
}
