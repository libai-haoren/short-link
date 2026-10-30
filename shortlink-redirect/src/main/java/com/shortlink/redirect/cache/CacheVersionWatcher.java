package com.shortlink.redirect.cache;

import com.shortlink.common.cache.CacheChannels;
import com.shortlink.common.cache.CacheKeys;
import com.shortlink.redirect.metrics.RedirectMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 全局缓存版本号轮询：Pub/Sub 的兜底。
 *
 * <p>Pub/Sub 是 at-most-once，实例离线、订阅断连、网络抖动都会丢消息。
 * admin 每次写缓存都会 {@code INCR sl:cache:ver}，这里每秒比对一次：
 * 版本变了就把本地缓存整体清掉。</p>
 *
 * <p>为什么是"整体清空"而不是"精确失效"：版本号不记录是哪个 key 变了。
 * 精确失效交给 Pub/Sub 的实时消息；版本号只负责"发现漏了，兜底纠偏"，
 * 它触发的频率极低（只在真的丢消息时），全量清空的代价可以接受。</p>
 *
 * <p>容错：单次读取失败只跳过这一轮，绝不终止整个流——否则 Redis 抖一下
 * 兜底机制就永久失效了，那比没有兜底更危险。</p>
 */
public class CacheVersionWatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CacheVersionWatcher.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final LinkCache linkCache;
    private final RedirectMetrics metrics;
    private final Duration pollInterval;

    private volatile String lastVersion;
    private volatile Disposable subscription;
    private volatile boolean running;

    public CacheVersionWatcher(ReactiveStringRedisTemplate redisTemplate,
                               LinkCache linkCache,
                               RedirectMetrics metrics,
                               Duration pollInterval) {
        this.redisTemplate = redisTemplate;
        this.linkCache = linkCache;
        this.metrics = metrics;
        // 兜一个下限：10ms 以内没有意义，只会打满 Redis
        this.pollInterval = pollInterval == null || pollInterval.toMillis() < 10
                ? Duration.ofSeconds(1)
                : pollInterval;
    }

    @Override
    public void start() {
        subscription = Flux.interval(pollInterval)
                .concatMap(tick -> redisTemplate.opsForValue().get(CacheKeys.CACHE_VERSION)
                        .onErrorResume(ex -> {
                            log.debug("read cache version failed, will retry next tick: {}", ex.toString());
                            return Mono.empty();
                        }))
                .subscribe(this::onVersion);
        running = true;
        log.info("cache version watcher started, interval={}", pollInterval);
    }

    private void onVersion(String version) {
        String current = version == null ? CacheChannels.VERSION_FALLBACK : version;
        String previous = lastVersion;
        lastVersion = current;

        // 第一次读到只做基线，不触发失效——否则每次重启都会白清一次缓存
        if (previous != null && !previous.equals(current)) {
            linkCache.invalidateAll();
            metrics.cacheEvicted(true);
            log.info("cache version changed {} -> {}, local cache cleared", previous, current);
        }
    }

    @Override
    public void stop() {
        Disposable current = subscription;
        if (current != null) {
            current.dispose();
            subscription = null;
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
