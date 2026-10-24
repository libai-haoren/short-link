package com.shortlink.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import java.time.Duration;

/**
 * 本地缓存工厂（L1）。
 *
 * <p>redirect 的性能几乎全押在这一层：命中率 96% 意味着只有 4% 的请求会碰 Redis。
 * 因此参数不是随手填的，都对应设计文档 5.3 的容量估算。</p>
 *
 * <p>为什么用 Caffeine 而不是 ConcurrentHashMap：需要容量上限（防 OOM）、
 * 过期策略、以及 W-TinyLFU 的命中率（比 LRU 高 10% 左右）。</p>
 */
public final class LocalCaches {

    private LocalCaches() {
    }

    /**
     * 构建短链元数据缓存。
     *
     * <p>TTL 加随机抖动是防雪崩的必要手段：同一时刻批量写入的 key 若 TTL 完全一致，
     * 会在同一秒集体失效，瞬间把流量全打到 Redis。</p>
     *
     * @param maximumSize     容量上限，按堆内存 1/8 与单条大小估算（10 万条 ≈ 80MB）
     * @param expireAfterWrite 基础 TTL
     * @param jitter           抖动上限，实际 TTL = base + random(0, jitter)
     */
    public static <V> Cache<String, V> newShortLinkCache(long maximumSize,
                                                         Duration expireAfterWrite,
                                                         Duration jitter) {
        return Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .recordStats()
                // 注意：expireAfterWrite 与 expireAfter 互斥。
                // Caffeine 若同时设置两者，会在 build() 时抛 IllegalStateException。
                // 这里只保留自定义 Expiry，由它同时承担 TTL 与抖动。
                .expireAfter(new JitterExpiry<>(expireAfterWrite, jitter))
                .build();
    }

    /** 便捷方法：拿命中率，用于上报 Micrometer。 */
    public static double hitRate(Cache<?, ?> cache) {
        CacheStats stats = cache.stats();
        long requests = stats.hitCount() + stats.missCount();
        return requests == 0 ? 0D : (double) stats.hitCount() / requests;
    }

    static final class JitterExpiry<K, V> implements com.github.benmanes.caffeine.cache.Expiry<K, V> {

        private final long baseNanos;
        private final long jitterNanos;

        JitterExpiry(Duration base, Duration jitter) {
            this.baseNanos = base.toNanos();
            this.jitterNanos = Math.max(0L, jitter.toNanos());
        }

        @Override
        public long expireAfterCreate(K key, V value, long currentTime) {
            return baseNanos + (jitterNanos == 0 ? 0 : java.util.concurrent.ThreadLocalRandom.current()
                    .nextLong(jitterNanos));
        }

        @Override
        public long expireAfterUpdate(K key, V value, long currentTime, long currentDuration) {
            return expireAfterCreate(key, value, currentTime);
        }

        @Override
        public long expireAfterRead(K key, V value, long currentTime, long currentDuration) {
            // 读不续期：短链修改必须能通过 TTL 自然失效，否则改了短链要等到重启才生效
            return currentDuration;
        }
    }
}
