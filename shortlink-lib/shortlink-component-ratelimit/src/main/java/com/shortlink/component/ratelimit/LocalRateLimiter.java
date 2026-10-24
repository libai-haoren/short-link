package com.shortlink.component.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地限流器：按 key 维护一组令牌桶。
 *
 * <p>为什么不走 Redis：限流本身如果每个请求都要一次 Redis 往返，等于给主链路加 1ms；
 * 而且 Redis 抖动时限流会先崩。本地限流零网络开销、天然高可用，
 * 代价是<b>集群维度不精确</b>（N 个实例 = N 倍阈值）。</p>
 *
 * <p>因此分工是：<b>网关做集群精确限流（防刷），redirect 做本地容量保护（防打满）</b>。
 * 目标不同，工具就不同。</p>
 *
 * <p>用 Caffeine 而不是裸 ConcurrentHashMap：桶会随 key（IP / 短码）无限增长，
 * 必须有过期与容量上限，否则就是一个慢性内存泄漏。</p>
 */
public final class LocalRateLimiter {

    private final Cache<String, TokenBucket> buckets;
    private final long capacity;
    private final long refillTokens;
    private final Duration period;

    /**
     * @param maximumKeys 最多维护多少个桶（超出后按 W-TinyLFU 淘汰）
     * @param expireAfterAccess 桶的空闲过期时间：热点过去后自动回收
     * @param capacity 单桶容量
     * @param refillTokens 补充令牌数
     * @param period 补充周期
     */
    public LocalRateLimiter(long maximumKeys,
                            Duration expireAfterAccess,
                            long capacity,
                            long refillTokens,
                            Duration period) {
        this.buckets = Caffeine.newBuilder()
                .maximumSize(maximumKeys)
                .expireAfterAccess(expireAfterAccess)
                .build();
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.period = period;
    }

    /**
     * 尝试获取许可。
     *
     * <p>注意 computeIfAbsent 的映射函数里不能做耗时操作（会持有 bin 锁），
     * 这里只是 new 一个对象，安全。</p>
     */
    public boolean tryAcquire(String key, int permits) {
        TokenBucket bucket = buckets.get(key, k -> new TokenBucket(capacity, refillTokens, period));
        return bucket != null && bucket.tryAcquire(permits);
    }

    /** 当前维护的桶数量，用于监控。 */
    public long size() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }

    /** 方便测试与排障。 */
    ConcurrentMap<String, TokenBucket> asMap() {
        return buckets.asMap();
    }
}
