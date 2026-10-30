package com.shortlink.redirect.cache;

import com.shortlink.common.cache.CacheChannels;
import com.shortlink.redirect.metrics.RedirectMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * 缓存失效订阅者：收到 admin 的广播就清掉本进程的 L1。
 *
 * <p>为什么不用 {@code @EventListener} 或定时任务：Pub/Sub 是 push 模型，
 * 消息到达即处理，延迟在毫秒级；轮询只能做到秒级，而且多实例下会有相位差。</p>
 *
 * <p>断线重连：{@code receive()} 返回的 Flux 在连接异常时会终止，
 * 用 {@code retryWhen} 每 5 秒重订阅一次。注意重订阅<b>不会</b>补发断连期间的消息——
 * 这段空缺由版本号轮询兜底。</p>
 *
 * <p>处理必须是纯内存操作：这里跑在 Lettuce 的订阅线程上，一旦做阻塞 IO 就会卡住整个订阅。</p>
 */
public class CacheSyncSubscriber implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CacheSyncSubscriber.class);

    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(5);

    private final ReactiveRedisMessageListenerContainer container;
    private final LinkCache linkCache;
    private final RedirectMetrics metrics;

    private volatile Disposable subscription;
    private volatile boolean running;

    public CacheSyncSubscriber(ReactiveRedisMessageListenerContainer container,
                               LinkCache linkCache,
                               RedirectMetrics metrics) {
        this.container = container;
        this.linkCache = linkCache;
        this.metrics = metrics;
    }

    @Override
    public void start() {
        subscription = container.receive(ChannelTopic.of(CacheChannels.LINK_EVICT))
                .map(ReactiveSubscription.Message::getMessage)
                .retryWhen(Retry.fixedDelay(Long.MAX_VALUE, RECONNECT_DELAY)
                        .doBeforeRetry(signal -> log.warn("resubscribing cache sync channel after error: {}",
                                signal.failure().toString())))
                .subscribe(this::handle, ex -> log.error("cache sync subscription terminated", ex));
        running = true;
        log.info("subscribed cache sync channel: {}", CacheChannels.LINK_EVICT);
    }

    private void handle(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        if (CacheChannels.isEvictAll(payload)) {
            linkCache.invalidateAll();
            metrics.cacheEvicted(true);
            log.info("received evict-all, local cache cleared");
        } else {
            linkCache.invalidate(payload);
            metrics.cacheEvicted(false);
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
