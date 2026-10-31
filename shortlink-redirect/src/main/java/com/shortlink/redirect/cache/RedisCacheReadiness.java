package com.shortlink.redirect.cache;

import com.shortlink.common.cache.CacheKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 基于 {@code sl:cache:ready} 标志的缓存就绪判定。
 *
 * <p><b>要解决的问题</b>：Redis 被 FLUSH、主从空切、或持久化丢失之后，所有 key 都不在，
 * 跳转链路会把每一条真实短链都判成 404。这比 503 更糟——它给用户和搜索引擎的是
 * "这个链接不存在"的<b>确定性结论</b>，而且不会触发任何错误告警。</p>
 *
 * <p><b>做法</b>：admin 在全量重建完成后写入 {@code sl:cache:ready}；
 * 本类周期性探测该标志。标志在 → 未命中判 404；标志缺失 → 未命中降级 503 并打点。
 * 标志本身存在 Redis 里，所以"进程重启不丢、Redis 被清空就丢"，语义天然对齐。</p>
 *
 * <p><b>初始状态是未就绪</b>：启动时先同步探一次再放流量，避免启动瞬间的空窗把所有
 * 未命中都放成 404。探测失败（Redis 不可用）保持上一次的判定，绝不因为一次抖动
 * 把闸门关死或开死——这条和版本号轮询是同一个原则。</p>
 */
public class RedisCacheReadiness implements CacheReadiness, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheReadiness.class);

    /** 启动探测的超时：Redis 正常时命令超时 50ms 会先生效，这里只是兜住"连不上"的情况。 */
    private static final Duration STARTUP_PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final Duration checkInterval;
    private final boolean enabled;

    private volatile boolean ready;
    private volatile boolean running;
    private volatile Disposable subscription;

    public RedisCacheReadiness(ReactiveStringRedisTemplate redisTemplate,
                               boolean enabled,
                               Duration checkInterval) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        // 兜一个下限：10ms 以内没有意义，只会打满 Redis
        this.checkInterval = checkInterval == null || checkInterval.toMillis() < 10
                ? Duration.ofSeconds(2)
                : checkInterval;
    }

    @Override
    public boolean isReady() {
        // 闸门关掉时退化成"永远就绪"，等价于改造前的行为
        return !enabled || ready;
    }

    @Override
    public void start() {
        if (!enabled) {
            running = true;
            log.info("cache readiness gate disabled, every cache miss will be served as 404");
            return;
        }
        probeOnceAtStartup();
        subscription = Flux.interval(checkInterval)
                .concatMap(tick -> probe()
                        .doOnNext(this::apply)
                        .onErrorResume(ex -> {
                            // 只跳过这一轮：一次抖动不能让闸门卡在错误状态上
                            log.debug("probe cache readiness failed, keep previous state: {}", ex.toString());
                            return Mono.empty();
                        }))
                .subscribe(null, ex -> log.error("cache readiness watcher terminated", ex));
        running = true;
        log.info("cache readiness watcher started, interval={}, ready={}", checkInterval, ready);
    }

    private void probeOnceAtStartup() {
        Boolean current = null;
        try {
            current = probe().block(STARTUP_PROBE_TIMEOUT);
        } catch (RuntimeException ex) {
            log.warn("probe cache readiness failed at startup, keep gate closed: {}", ex.toString());
        }
        if (current != null) {
            apply(current);
        }
    }

    /** key 存在即就绪；不存在（或读不到）都不是就绪。 */
    private Mono<Boolean> probe() {
        return redisTemplate.hasKey(CacheKeys.CACHE_READY).defaultIfEmpty(false);
    }

    private void apply(boolean current) {
        boolean previous = ready;
        ready = current;
        if (previous == current) {
            return;
        }
        if (current) {
            log.info("cache readiness gate opened: {} is present", CacheKeys.CACHE_READY);
        } else {
            log.warn("cache readiness gate CLOSED: {} is absent, cache miss will be served as 503 "
                    + "instead of 404 (run cache rebuild to reopen)", CacheKeys.CACHE_READY);
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
