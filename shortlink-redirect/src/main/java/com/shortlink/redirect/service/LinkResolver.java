package com.shortlink.redirect.service;

import com.shortlink.common.cache.CacheKeys;
import com.shortlink.component.codec.ShortCodeCodec;
import com.shortlink.redirect.cache.CacheReadiness;
import com.shortlink.redirect.cache.CacheUnavailableException;
import com.shortlink.redirect.cache.CorruptCacheDataException;
import com.shortlink.redirect.cache.LinkCache;
import com.shortlink.redirect.cache.LinkSnapshot;
import com.shortlink.redirect.metrics.RedirectMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 短链解析：格式校验 → 缓存查询 → 状态判定 → 配额判定。
 *
 * <p>整条链路<b>不抛业务异常</b>，所有失败都收敛成 {@link ResolveResult}。
 * 两个原因：异常构造要填栈，在热路径上是浪费；"链接不存在"本来就是正常分支。</p>
 *
 * <p>依赖故障（error 信号）在这一层被消费掉，转成 {@code UNAVAILABLE}，
 * 保证控制器只需要处理一种返回值。</p>
 *
 * <p><b>未命中不等于 404</b>：先看 {@link CacheReadiness}。Redis 被清空或主从空切时
 * 所有 key 都不在，直接判 404 等于给用户"这个链接不存在"的确定性结论；
 * 就绪标志缺失时降级成 503 并打点，把静默的数据事故变成可告警的可用性事故。</p>
 */
public class LinkResolver {

    private static final Logger log = LoggerFactory.getLogger(LinkResolver.class);

    /** 配额计数保留 7 天：足够覆盖一次营销活动，又不至于永久占内存。 */
    private static final Duration QUOTA_TTL = Duration.ofDays(7);

    private final LinkCache linkCache;
    private final RedirectMetrics metrics;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final CacheReadiness readiness;

    public LinkResolver(LinkCache linkCache,
                        RedirectMetrics metrics,
                        ReactiveStringRedisTemplate redisTemplate,
                        CacheReadiness readiness) {
        this.linkCache = linkCache;
        this.metrics = metrics;
        this.redisTemplate = redisTemplate;
        this.readiness = readiness;
    }

    /**
     * 解析短码。
     *
     * @param shortCode 路径上的短码，可能为 null 或任意长度
     * @return 永远非空，不会以 error 结束
     */
    public Mono<ResolveResult> resolve(String shortCode) {
        if (!ShortCodeCodec.isValidFormat(shortCode)) {
            // 最便宜的一层：非法格式连 Redis 都不碰
            return Mono.just(ResolveResult.invalid());
        }
        return linkCache.get(shortCode)
                .flatMap(snapshot -> {
                    ResolveResult result = evaluate(snapshot, System.currentTimeMillis());
                    if (result.outcome() == ResolveResult.Outcome.UNAVAILABLE) {
                        // 走到这里说明缓存里的 longUrl 不是 http(s)，属于数据损坏
                        metrics.recordCorrupt();
                        log.error("cache entry is not redirectable, code={}", shortCode);
                        return Mono.just(result);
                    }
                    if (result.outcome() != ResolveResult.Outcome.FOUND || snapshot.maxClick() <= 0) {
                        return Mono.just(result);
                    }
                    // 只有配了 maxClick 的短链才多这一次 Redis INCR
                    return consumeQuota(shortCode, snapshot.maxClick())
                            .map(allowed -> allowed ? result : ResolveResult.quotaExceeded());
                })
                .switchIfEmpty(Mono.defer(this::onCacheMiss))
                .onErrorResume(CorruptCacheDataException.class, ex -> {
                    metrics.recordCorrupt();
                    log.error("corrupt cache entry, code={}, reason={}", shortCode, ex.getMessage());
                    return Mono.just(ResolveResult.unavailable());
                })
                .onErrorResume(CacheUnavailableException.class, ex -> {
                    metrics.recordRedisError();
                    // 用 debug：Redis 故障时每个请求都会走到这里，warn 会刷爆日志
                    log.debug("cache unavailable, code={}, reason={}", shortCode, ex.getMessage());
                    return Mono.just(ResolveResult.unavailable());
                })
                .onErrorResume(ex -> {
                    log.error("unexpected error while resolving, code={}", shortCode, ex);
                    return Mono.just(ResolveResult.unavailable());
                });
    }

    /**
     * 缓存未命中时的出口。
     *
     * <p>就绪标志存在 → 缓存数据完整，这条短码确实不存在（404）；
     * 标志缺失 → 可能是 Redis 被清空，判 503 并打点，交给告警和 rebuild 处理。</p>
     *
     * <p>注意不在这里打日志：Redis 真的空了的时候每个请求都会命中这段逻辑，
     * 按请求打日志会先把日志系统打垮。状态变化由 {@link CacheReadiness} 的实现记录一次。</p>
     */
    private Mono<ResolveResult> onCacheMiss() {
        boolean cacheReady = readiness.isReady();
        if (!cacheReady) {
            metrics.recordCacheNotReady();
        }
        return Mono.just(onMiss(cacheReady));
    }

    /** 纯函数，便于单测：就绪 → 404，未就绪 → 503。 */
    static ResolveResult onMiss(boolean cacheReady) {
        return cacheReady ? ResolveResult.notFound() : ResolveResult.unavailable();
    }

    /**
     * 消耗一次访问配额。
     *
     * <p>用 {@code INCR} 而不是"先读后写"：并发下两个请求可能都读到 maxClick-1，
     * 结果多放过去一批。INCR 是原子的，最多在边界上超出 1~2 次（可接受，也写进了文档）。</p>
     *
     * <p>Redis 故障时<b>放行</b>：这是浏览器正在跳转的路径，宁可少限一次，
     * 也不能因为 Redis 抖动把正常用户挡在门外。</p>
     */
    private Mono<Boolean> consumeQuota(String shortCode, long maxClick) {
        String key = CacheKeys.clickQuota(shortCode);
        return redisTemplate.opsForValue()
                .increment(key)
                .flatMap(count -> {
                    if (count != null && count == 1L) {
                        // 第一次计数时挂上 TTL，避免这些 key 永久堆积
                        return redisTemplate.expire(key, QUOTA_TTL).thenReturn(count);
                    }
                    return Mono.just(count == null ? 0L : count);
                })
                .map(count -> count <= maxClick)
                .onErrorResume(ex -> {
                    log.warn("quota check failed, allow redirect: code={}, reason={}", shortCode, ex.toString());
                    return Mono.just(true);
                });
    }

    /**
     * 纯函数状态判定，不依赖 Spring，可脱离容器单测。
     *
     * <p>判定顺序有讲究：先看是否停用，再看是否过期。停用是运营主动行为，
     * 语义上优先于"自然过期"，返回码也更准确（403 而不是 410）。</p>
     *
     * @param nowMillis 当前时间，显式传入而不是内部取，才能测边界
     */
    public static ResolveResult evaluate(LinkSnapshot snapshot, long nowMillis) {
        // 只有明确等于 1 才算启用：未知状态一律按停用处理（fail-closed）
        if (snapshot.status() != 1) {
            return ResolveResult.disabled();
        }
        // 闭区间：正好等于过期时刻就已经不可跳转
        if (snapshot.expireTimeMillis() > 0 && nowMillis >= snapshot.expireTimeMillis()) {
            return ResolveResult.expired();
        }
        if (!isRedirectable(snapshot.longUrl())) {
            return ResolveResult.unavailable();
        }
        return ResolveResult.found(snapshot.longUrl());
    }

    /** 防御性校验：admin 建链时已限制协议，但缓存可能被误写。 */
    private static boolean isRedirectable(String longUrl) {
        if (longUrl == null) {
            return false;
        }
        return longUrl.regionMatches(true, 0, "https://", 0, 8)
                || longUrl.regionMatches(true, 0, "http://", 0, 7);
    }
}
