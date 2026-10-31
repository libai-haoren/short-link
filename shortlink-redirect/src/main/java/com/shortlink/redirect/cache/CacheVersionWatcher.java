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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局缓存版本号轮询：Pub/Sub 的兜底。
 *
 * <p>Pub/Sub 是 at-most-once，实例离线、订阅断连、网络抖动都会丢消息。
 * 每次写操作都会 {@code INCR sl:cache:ver} 并发一条失效消息，两者 1:1 配对。
 * 本类每秒读一次版本号，用 {@link VersionGapTracker} 比对"版本增量"与
 * "实际收到的消息数"：<b>只有对不上才全量失效</b>。</p>
 *
 * <p><b>不是"版本一变就全清"</b>。早期实现是那样的，后果是每次正常写入都会让所有实例
 * 清空 10 万条 L1——精确失效被全清盖掉，命中率随写流量下降，批量导入更是灾难。
 * 现在正常写入是零代价，只有真的丢了消息（实例重启、订阅断连）才会触发全清。</p>
 *
 * <p>代价：丢消息的发现时间从 1s 变成约 2s（需要一轮延迟判定，见 {@link VersionGapTracker}）。
 * 这个交换是划算的——用 1 秒的发现延迟，换掉每次写入都全清。</p>
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

    /** 自上次轮询以来实际收到的失效消息数，由订阅端累加。 */
    private final AtomicLong received = new AtomicLong();

    private final VersionGapTracker tracker = new VersionGapTracker();

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

    /**
     * 订阅端每处理完一条失效消息调用一次（含 {@code *} 全清消息）。
     *
     * <p>这里的计数是"这条消息我收到了"的证据，用来和版本号的增量对账。</p>
     */
    public void onMessageReceived() {
        received.incrementAndGet();
    }

    @Override
    public void start() {
        // 首次触发加随机延迟：同一批实例（滚动发布/同时启动）如果相位一致，会同时读同一个
        // key、同时全清、再同时回源。抖动一下把这一波打散。
        Duration firstDelay = Duration.ofMillis(
                ThreadLocalRandom.current().nextLong(Math.max(1L, pollInterval.toMillis())));

        subscription = Flux.interval(firstDelay, pollInterval)
                .concatMap(tick -> probe())
                .subscribe(this::onVersion, ex -> log.error("cache version watcher terminated", ex));

        running = true;
        log.info("cache version watcher started, interval={}, firstDelay={}ms",
                pollInterval, firstDelay.toMillis());
    }

    /** key 不存在按 {@code 0} 参与比较（Redis 被 FLUSH 后就是这种情况）。 */
    private Mono<String> probe() {
        return redisTemplate.opsForValue().get(CacheKeys.CACHE_VERSION)
                .defaultIfEmpty(CacheChannels.VERSION_FALLBACK)
                .onErrorResume(ex -> {
                    log.debug("read cache version failed, will retry next tick: {}", ex.toString());
                    return Mono.empty();
                });
    }

    private void onVersion(String version) {
        long current;
        try {
            current = Long.parseLong(version.trim());
        } catch (NumberFormatException ex) {
            log.warn("unexpected cache version value: {}", version);
            return;
        }

        long receivedSinceLastPoll = received.getAndSet(0L);
        if (tracker.onPoll(current, receivedSinceLastPoll)) {
            linkCache.invalidateAll();
            metrics.cacheEvicted(RedirectMetrics.EvictSource.VERSION);
            log.info("cache version gap confirmed (version={}, received={}), local cache cleared",
                    current, receivedSinceLastPoll);
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
