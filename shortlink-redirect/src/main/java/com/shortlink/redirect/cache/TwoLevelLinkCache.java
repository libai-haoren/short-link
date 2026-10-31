package com.shortlink.redirect.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.shortlink.redirect.metrics.RedirectMetrics;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 两级缓存：Caffeine（L1）+ Redis（L2）。
 *
 * <p>性能几乎全押在 L1 上：命中率 95% 意味着只有 5% 的请求产生网络开销。</p>
 *
 * <p>一致性由两条通道保证（都不在请求路径上）：</p>
 * <ol>
 *   <li><b>Pub/Sub 实时失效</b>：admin 写完 Redis 后广播短码，本类把对应 key 删掉，
 *       下一次请求回到 Redis 读新值——延迟通常在毫秒级；</li>
 *   <li><b>版本号对账</b>：万一消息丢了（实例离线、订阅断连），每秒比对一次版本增量和
 *       实收消息数，确认丢了才整体失效。</li>
 * </ol>
 *
 * <p><b>回填不是无条件的</b>。read-through 有一个经典竞态：请求在 Redis 读到旧值后、
 * 还没写进 L1 之前，失效消息到了——此时 L1 里本来就没这个 key，"删"是空操作，
 * 随后旧值被写回去，而且不会再有消息来清它，会脏到 TTL 为止。两个保护：</p>
 * <ul>
 *   <li><b>失效水位</b>：记录"这个 key 什么时候被失效过"，回填时若发现读取开始之后
 *       发生过失效，直接放弃这次回填；</li>
 *   <li><b>版本比较</b>：两个并发回填，版本低的不会覆盖版本高的。</li>
 * </ul>
 *
 * <p>不做"负缓存"：扫描器用随机短码，同一个不存在的码几乎不会被重复访问，
 * 缓存未命中结果的命中率接近 0，只会增加复杂度。</p>
 */
public class TwoLevelLinkCache implements LinkCache {

    /** 失效水位的保留时长：只需覆盖"一次读取从开始到回填"的窗口，30s 绰绰有余。 */
    private static final Duration EVICTION_MEMORY = Duration.ofSeconds(30);

    private final Cache<String, LinkSnapshot> localCache;
    private final LinkCache delegate;
    private final RedirectMetrics metrics;

    /** key → 最后一次失效的 nanoTime，只用于判定"这次回填是不是已经过时"。 */
    private final Cache<String, Long> evictedAt;

    /** 最后一次全清的时间点；0 表示从未全清。 */
    private volatile long evictAllAt;

    public TwoLevelLinkCache(Cache<String, LinkSnapshot> localCache,
                             LinkCache delegate,
                             RedirectMetrics metrics) {
        this.localCache = localCache;
        this.delegate = delegate;
        this.metrics = metrics;
        this.evictedAt = Caffeine.newBuilder()
                // 与 L1 同量级即可：水位只在窗口内有意义，超出自然淘汰
                .maximumSize(100_000L)
                .expireAfterWrite(EVICTION_MEMORY)
                .build();
    }

    @Override
    public Mono<LinkSnapshot> get(String shortCode) {
        long startNanos = System.nanoTime();

        LinkSnapshot cached = localCache.getIfPresent(shortCode);
        if (cached != null) {
            metrics.cacheHit(true);
            return Mono.just(cached);
        }
        metrics.cacheHit(false);
        // 注意：doOnNext 只在成功命中时执行；Redis 报错时不会污染 L1
        return delegate.get(shortCode).doOnNext(snapshot -> refill(shortCode, snapshot, startNanos));
    }

    /**
     * 把从 Redis 读到的快照回填到 L1，但拒绝两种情况：
     * 读取期间该 key 被失效过、或本地已有更高版本。
     */
    private void refill(String shortCode, LinkSnapshot snapshot, long startNanos) {
        long allEvicted = evictAllAt;
        if (allEvicted != 0L && allEvicted - startNanos > 0) {
            // 读取期间发生过全清（重建、版本号对账），这次读取的结果可能已经过时
            return;
        }
        Long evicted = evictedAt.getIfPresent(shortCode);
        if (evicted != null && evicted - startNanos > 0) {
            // 读取期间这个 key 被精确失效过，放弃回填，等下一次请求重新读
            return;
        }
        // 并发回填时保留版本更高的那个
        localCache.asMap().merge(shortCode, snapshot,
                (existing, incoming) -> incoming.version() >= existing.version() ? incoming : existing);
    }

    @Override
    public void invalidate(String shortCode) {
        localCache.invalidate(shortCode);
        evictedAt.put(shortCode, System.nanoTime());
    }

    @Override
    public void invalidateAll() {
        localCache.invalidateAll();
        evictAllAt = System.nanoTime();
    }

    /** 仅供监控：当前本地缓存条目数。 */
    public long estimatedSize() {
        return localCache.estimatedSize();
    }
}
