package com.shortlink.redirect.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.shortlink.common.cache.IpBlacklist;
import com.shortlink.common.cache.LocalCaches;
import com.shortlink.common.mq.EventPublisher;
import com.shortlink.component.ratelimit.LocalRateLimiter;
import com.shortlink.redirect.cache.CacheSyncSubscriber;
import com.shortlink.redirect.cache.CacheVersionWatcher;
import com.shortlink.redirect.cache.LinkCache;
import com.shortlink.redirect.cache.LinkSnapshot;
import com.shortlink.redirect.cache.RedisLinkCache;
import com.shortlink.redirect.cache.TwoLevelLinkCache;
import com.shortlink.redirect.metrics.RedirectMetrics;
import com.shortlink.redirect.service.LinkResolver;
import com.shortlink.redirect.web.IpBlacklistWebFilter;
import com.shortlink.redirect.stats.ClickTracker;
import com.shortlink.redirect.web.LocalRateLimitWebFilter;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;

import java.time.Duration;

/**
 * 跳转服务装配。
 *
 * <p>每个能力都有独立开关（L1 / 缓存同步 / 限流 / 黑名单），
 * 压测与排障时可以单独关掉某一层看影响，不用改代码重新打包。</p>
 */
@Configuration
@EnableConfigurationProperties(RedirectProperties.class)
public class RedirectConfig {

    @Bean
    public LinkCache linkCache(ReactiveStringRedisTemplate redisTemplate,
                               RedirectProperties properties,
                               RedirectMetrics metrics) {
        LinkCache redisCache = new RedisLinkCache(redisTemplate);

        RedirectProperties.L1 l1 = properties.l1();
        if (!l1.enabled()) {
            return redisCache;
        }

        Cache<String, LinkSnapshot> localCache =
                LocalCaches.newShortLinkCache(l1.maxSize(), l1.ttl(), l1.jitter());
        return new TwoLevelLinkCache(localCache, redisCache, metrics);
    }

    @Bean
    public LinkResolver linkResolver(LinkCache linkCache,
                                     RedirectMetrics metrics,
                                     ReactiveStringRedisTemplate redisTemplate) {
        return new LinkResolver(linkCache, metrics, redisTemplate);
    }

    // ------------------------------------------------------------------
    // 缓存同步
    // ------------------------------------------------------------------

    /**
     * 订阅容器。只有开启同步时才创建——容器本身会占用一条专用连接。
     */
    @Bean
    @ConditionalOnProperty(name = "shortlink.redirect.cache-sync.enabled",
            havingValue = "true", matchIfMissing = true)
    public ReactiveRedisMessageListenerContainer cacheSyncListenerContainer(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveRedisMessageListenerContainer(connectionFactory);
    }

    @Bean
    @ConditionalOnProperty(name = "shortlink.redirect.cache-sync.enabled",
            havingValue = "true", matchIfMissing = true)
    public CacheSyncSubscriber cacheSyncSubscriber(ReactiveRedisMessageListenerContainer container,
                                                   LinkCache linkCache,
                                                   RedirectMetrics metrics) {
        return new CacheSyncSubscriber(container, linkCache, metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "shortlink.redirect.cache-sync.enabled",
            havingValue = "true", matchIfMissing = true)
    public CacheVersionWatcher cacheVersionWatcher(ReactiveStringRedisTemplate redisTemplate,
                                                   LinkCache linkCache,
                                                   RedirectMetrics metrics,
                                                   RedirectProperties properties) {
        return new CacheVersionWatcher(redisTemplate, linkCache, metrics,
                properties.cacheSync().versionPollInterval());
    }

    // ------------------------------------------------------------------
    // IP 黑名单
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnProperty(name = "shortlink.redirect.blacklist.enabled",
            havingValue = "true", matchIfMissing = true)
    public IpBlacklist ipBlacklist(ReactiveStringRedisTemplate redisTemplate,
                                   RedirectProperties properties) {
        return new IpBlacklist(redisTemplate, properties.blacklist().refreshInterval());
    }

    @Bean
    @ConditionalOnProperty(name = "shortlink.redirect.blacklist.enabled",
            havingValue = "true", matchIfMissing = true)
    public IpBlacklistWebFilter ipBlacklistWebFilter(IpBlacklist ipBlacklist) {
        return new IpBlacklistWebFilter(ipBlacklist);
    }

    // ------------------------------------------------------------------
    // 本地限流
    // ------------------------------------------------------------------

    /**
     * 两个令牌桶：实例总入口 + 单短码。
     *
     * <p>单码桶的 key 数量随访问过的短码增长，必须设上限并让它过期，
     * 否则就是一个慢性内存泄漏——Caffeine 在这里不是为了性能，是为了兜住内存。</p>
     */
    @Bean
    @ConditionalOnProperty(name = "shortlink.redirect.rate-limit.enabled",
            havingValue = "true", matchIfMissing = true)
    public LocalRateLimitWebFilter localRateLimitWebFilter(RedirectProperties properties,
                                                           MeterRegistry meterRegistry) {
        RedirectProperties.RateLimit config = properties.rateLimit();

        LocalRateLimiter globalLimiter = new LocalRateLimiter(
                1L,
                Duration.ofMinutes(5),
                config.globalCapacity(),
                config.globalCapacity(),
                Duration.ofSeconds(1));

        long maxCodeKeys = Math.max(10_000L, config.perCodeCapacity() * 20);
        LocalRateLimiter perCodeLimiter = new LocalRateLimiter(
                maxCodeKeys,
                Duration.ofMinutes(1),
                config.perCodeCapacity(),
                config.perCodeCapacity(),
                config.perCodeWindow());

        return new LocalRateLimitWebFilter(globalLimiter, perCodeLimiter, meterRegistry);
    }
    /**
     * 点击埋点采集器。
     *
     * <p>没有 KafkaTemplate（未配 MQ）时 {@code publisher} 为 null，采集器自动降级为禁用，
     * 跳转链路完全不受影响。</p>
     */
    @Bean
    public ClickTracker clickTracker(ObjectProvider<EventPublisher> publisherProvider,
                                     RedirectProperties properties,
                                     MeterRegistry meterRegistry) {
        RedirectProperties.Stats stats = properties.stats();
        return new ClickTracker(publisherProvider.getIfAvailable(),
                stats.enabled(), stats.ipSalt(), stats.queueSize(), stats.batchSize(),
                Duration.ofMillis(200), meterRegistry);
    }
}