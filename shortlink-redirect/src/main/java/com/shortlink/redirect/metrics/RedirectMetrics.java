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
    private final Counter cacheHit;
    private final Counter cacheMiss;
    private final Counter redisErrors;
    private final Counter corruptEntries;
    private final Counter evictByMessage;
    private final Counter evictByVersion;
    private final Timer latency;

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
        this.evictByMessage = Counter.builder("shortlink.cache.evict.total")
                .description("本地缓存失效次数")
                .tag("source", "pubsub")
                .register(registry);
        this.evictByVersion = Counter.builder("shortlink.cache.evict.total")
                .description("本地缓存失效次数")
                .tag("source", "version")
                .register(registry);
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

    /**
     * 记录一次本地缓存失效。
     *
     * @param fromVersionPoll true 表示由版本号兜底触发（意味着 Pub/Sub 可能丢过消息，
     *                        这个值持续增长说明订阅链路有问题）
     */
    public void cacheEvicted(boolean fromVersionPoll) {
        (fromVersionPoll ? evictByVersion : evictByMessage).increment();
    }

    public void recordLatency(long nanos) {
        latency.record(Duration.ofNanos(nanos));
    }
}
