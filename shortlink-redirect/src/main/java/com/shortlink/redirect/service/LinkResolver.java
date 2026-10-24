package com.shortlink.redirect.service;

import com.shortlink.component.codec.ShortCodeCodec;
import com.shortlink.redirect.cache.CacheUnavailableException;
import com.shortlink.redirect.cache.CorruptCacheDataException;
import com.shortlink.redirect.cache.LinkCache;
import com.shortlink.redirect.cache.LinkSnapshot;
import com.shortlink.redirect.metrics.RedirectMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 短链解析：格式校验 → 缓存查询 → 状态判定。
 *
 * <p>整条链路<b>不抛业务异常</b>，所有失败都收敛成 {@link ResolveResult}。
 * 两个原因：异常构造要填栈，在热路径上是浪费；"链接不存在"本来就是正常分支。</p>
 *
 * <p>依赖故障（error 信号）在这一层被消费掉，转成 {@code UNAVAILABLE}，
 * 保证控制器只需要处理一种返回值。</p>
 */
public class LinkResolver {

    private static final Logger log = LoggerFactory.getLogger(LinkResolver.class);

    private final LinkCache linkCache;
    private final RedirectMetrics metrics;

    public LinkResolver(LinkCache linkCache, RedirectMetrics metrics) {
        this.linkCache = linkCache;
        this.metrics = metrics;
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
                .map(snapshot -> {
                    ResolveResult result = evaluate(snapshot, System.currentTimeMillis());
                    if (result.outcome() == ResolveResult.Outcome.UNAVAILABLE) {
                        // 走到这里说明缓存里的 longUrl 不是 http(s)，属于数据损坏
                        metrics.recordCorrupt();
                        log.error("cache entry is not redirectable, code={}", shortCode);
                    }
                    return result;
                })
                .defaultIfEmpty(ResolveResult.notFound())
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
