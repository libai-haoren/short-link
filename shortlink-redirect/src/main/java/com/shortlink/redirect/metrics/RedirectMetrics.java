package com.shortlink.redirect.metrics;

import com.shortlink.redirect.service.ResolveResult.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 跳转链路的指标出口。
 *
 * <p>计数器在构造时一次性注册好并缓存引用，不在请求路径上按名字查表——
 * Micrometer 的 {@code counter(name, tags...)} 虽然是幂等的，但每次调用都要做
 * 标签拼装与 Map 查找，在 10w QPS 下是实打实的开销。</p>
 */
@Component
public class RedirectMetrics {

    private final Map<Outcome, Counter> outcomeCounters = new EnumMap<>(Outcome.class);
    private final Map<EvictSource, Counter> evictCounters = new EnumMap<>(EvictSource.class);
    private final Counter cacheHit;
    private final Counter cacheMiss;
    private final Counter redisErrors;
    private final Counter corruptEntries;
    private final Counter cacheNotReady;
    private final Timer latency;

    /**
     * 本地缓存失效的来源。
     *
     * <p>必须<b>三种</b>而不是两种：收到精确失效、收到全清消息（重建后）、版本号兜底，
     * 这是三个完全不同的事件，混成一个计数会让看板说谎。
     * 尤其是"版本号兜底次数持续增长"意味着 Pub/Sub 在丢消息，而重建后的全清是正常运维动作。</p>
     */
    public enum EvictSource {
        /** 收到精确失效消息：只清一个短码。日常写操作走这条。 */
        PUBSUB,
        /** 收到全清消息 {@code *}：典型场景是缓存重建完成后。 */
        EVICT_ALL,
        /** 版本号轮询发现变化：兜底路径，说明 Pub/Sub 可能丢过消息。 */
        VERSION
    }

    public RedirectMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            outcomeCounters.put(outcome, Counter.builder("shortlink.redirect.total")
                    .description("跳转结果分布")
                    .tag("result", outcome.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
        this.cacheHit = Counter.builder("shortlink.cache.l1.total")
                .tag("result", "hit").register(registry);
        this.cacheMiss = Counter.builder("shortlink.cache.l1.total")
                .tag("result", "miss").register(registry);
        this.redisErrors = Counter.builder("shortlink.cache.error.total")
                .description("Redis 读取失败次数")
                .register(registry);
        this.corruptEntries = Counter.builder("shortlink.redirect.corrupt.total")
                .description("缓存数据损坏次数，非零即需告警")
                .register(registry);
        this.cacheNotReady = Counter.builder("shortlink.cache.not_ready.total")
                .description("缓存就绪标志缺失导致未命中降级为 503 的次数，非零即需告警")
                .register(registry);
        for (EvictSource source : EvictSource.values()) {
            evictCounters.put(source, Counter.builder("shortlink.cache.evict.total")
                    .description("本地缓存失效次数")
                    // EVICT_ALL -> evict_all，与既有的 pubsub / version 保持同一命名风格
                    .tag("source", source.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
        this.latency = Timer.builder("shortlink.redirect.latency")
                .description("跳转端到端耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void recordOutcome(Outcome outcome) {
        Counter counter = outcomeCounters.get(outcome);
        if (counter != null) {
            counter.increment();
        }
    }

    public void cacheHit(boolean hit) {
        (hit ? cacheHit : cacheMiss).increment();
    }

    public void recordRedisError() {
        redisErrors.increment();
    }

    public void recordCorrupt() {
        corruptEntries.increment();
    }

    /** 缓存未命中且就绪标志缺失：这次请求被降级成 503，而不是谎报 404。 */
    public void recordCacheNotReady() {
        cacheNotReady.increment();
    }

    /**
     * 记录一次本地缓存失效。
     *
     * @param source 失效来源，决定 tag 值。用枚举而不是 boolean：
     *               之前用 {@code boolean fromVersionPoll} 时，"收到全清消息"被错记成了
     *               "版本号兜底"，导致兜底次数虚高、掩盖真实的 Pub/Sub 丢消息
     */
    public void cacheEvicted(EvictSource source) {
        Counter counter = evictCounters.get(source);
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordLatency(long nanos) {
        latency.record(Duration.ofNanos(nanos));
    }
}
