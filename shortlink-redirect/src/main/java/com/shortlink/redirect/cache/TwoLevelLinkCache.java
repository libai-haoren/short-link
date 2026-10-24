package com.shortlink.redirect.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.shortlink.redirect.metrics.RedirectMetrics;
import reactor.core.publisher.Mono;

/**
 * 两级缓存：Caffeine（L1）+ Redis（L2）。
 *
 * <p>性能几乎全押在 L1 上：命中率 95% 意味着只有 5% 的请求产生网络开销。</p>
 *
 * <p>一致性代价要说清楚：本条链路<b>没有主动失效</b>，L1 只能靠 TTL 自然过期
 * （默认 10 分钟）。也就是说改链、封禁最长要 10 分钟才在所有实例生效。
 * 这是刻意的取舍——redirect 连不上 DB，等 V3 接入 MQ 广播后才会降到秒级。</p>
 *
 * <p>不做"负缓存"：扫描器用的是随机短码，同一个不存在的码几乎不会被重复访问，
 * 缓存未命中结果命中率接近 0，只会增加复杂度。</p>
 */
public class TwoLevelLinkCache implements LinkCache {

    private final Cache<String, LinkSnapshot> localCache;
    private final LinkCache delegate;
    private final RedirectMetrics metrics;

    public TwoLevelLinkCache(Cache<String, LinkSnapshot> localCache,
                             LinkCache delegate,
                             RedirectMetrics metrics) {
        this.localCache = localCache;
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public Mono<LinkSnapshot> get(String shortCode) {
        LinkSnapshot cached = localCache.getIfPresent(shortCode);
        if (cached != null) {
            metrics.cacheHit(true);
            return Mono.just(cached);
        }
        metrics.cacheHit(false);
        // 注意：doOnNext 只在成功命中时执行；Redis 报错时不会污染 L1
        return delegate.get(shortCode).doOnNext(snapshot -> localCache.put(shortCode, snapshot));
    }
}
