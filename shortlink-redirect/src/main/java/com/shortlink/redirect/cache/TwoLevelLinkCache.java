package com.shortlink.redirect.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.shortlink.redirect.metrics.RedirectMetrics;
import reactor.core.publisher.Mono;

/**
 * 两级缓存：Caffeine（L1）+ Redis（L2）。
 *
 * <p>性能几乎全押在 L1 上：命中率 95% 意味着只有 5% 的请求产生网络开销。</p>
 *
 * <p>一致性由两条通道保证（都不在请求路径上）：</p>
 * <ol>
 *   <li><b>Pub/Sub 实时失效</b>：admin 写完 Redis 后广播短码，本类把对应 key 删掉，
 *       下一次请求回到 Redis 读新值——延迟通常在毫秒级；</li>
 *   <li><b>版本号轮询兜底</b>：万一消息丢了（实例离线、订阅断连），
 *       每秒比对一次全局版本号，变化就整体失效。</li>
 * </ol>
 *
 * <p>不做"负缓存"：扫描器用随机短码，同一个不存在的码几乎不会被重复访问，
 * 缓存未命中结果的命中率接近 0，只会增加复杂度。</p>
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

    @Override
    public void invalidate(String shortCode) {
        localCache.invalidate(shortCode);
    }

    @Override
    public void invalidateAll() {
        localCache.invalidateAll();
    }

    /** 仅供监控：当前本地缓存条目数。 */
    public long estimatedSize() {
        return localCache.estimatedSize();
    }
}
