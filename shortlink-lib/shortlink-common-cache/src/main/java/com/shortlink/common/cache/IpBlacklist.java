package com.shortlink.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IP 黑名单（Redis Set + 本地快照）。
 *
 * <p><b>为什么不在每个请求上查 Redis</b>：黑名单是"少数命中"的判定，但调用频率等于全站 QPS。
 * 每请求一次 {@code SISMEMBER} 就是给主链路固定加一次网络往返。这里改成<b>定时拉全量</b>：
 * 几秒同步一次，判断退化成内存 {@code Set#contains}，零网络开销。</p>
 *
 * <p>代价是生效延迟 = 刷新间隔（默认 2s）。对"拉黑恶意 IP"这种场景完全可以接受——
 * 封禁一个 IP 不需要毫秒级。</p>
 *
 * <p><b>拉取失败时保留旧快照，不清空</b>。清空的后果是"Redis 抖一下，所有黑名单瞬间失效"，
 * 那比稍微旧一点的数据危险得多。</p>
 */
public class IpBlacklist implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IpBlacklist.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final Duration refreshInterval;

    private volatile Set<String> snapshot = Set.of();
    private final AtomicLong version = new AtomicLong();

    private volatile Disposable subscription;
    private volatile boolean running;

    public IpBlacklist(ReactiveStringRedisTemplate redisTemplate, Duration refreshInterval) {
        this.redisTemplate = redisTemplate;
        this.refreshInterval = refreshInterval == null || refreshInterval.toMillis() < 100
                ? Duration.ofSeconds(2)
                : refreshInterval;
    }

    /** 判断是否命中黑名单。纯内存操作，可以放在请求热路径上。 */
    public boolean isBlocked(String ip) {
        return ip != null && !ip.isBlank() && snapshot.contains(ip);
    }

    public int size() {
        return snapshot.size();
    }

    /** 快照版本号，每次刷新 +1，便于排查"到底加载上没有"。 */
    public long version() {
        return version.get();
    }

    @Override
    public void start() {
        // 立即拉一次，然后按间隔刷新；用 concatMap 保证不会并发拉取
        subscription = Flux.interval(Duration.ZERO, refreshInterval)
                .concatMap(tick -> redisTemplate.opsForSet().members(CacheKeys.BLACK_IP)
                        .collectList()
                        // 失败发 empty：doOnNext 不会执行，旧快照原样保留
                        .onErrorResume(ex -> {
                            log.warn("refresh ip blacklist failed, keep previous snapshot(size={}): {}",
                                    snapshot.size(), ex.toString());
                            return Mono.empty();
                        }))
                .subscribe(this::replaceSnapshot, ex -> log.error("ip blacklist watcher terminated", ex));
        running = true;
        log.info("ip blacklist watcher started, interval={}", refreshInterval);
    }

    private void replaceSnapshot(java.util.List<String> members) {
        Set<String> next = Set.copyOf(members);
        boolean changed = !next.equals(snapshot);
        snapshot = next;
        if (changed) {
            version.incrementAndGet();
            log.info("ip blacklist refreshed, size={}", next.size());
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
