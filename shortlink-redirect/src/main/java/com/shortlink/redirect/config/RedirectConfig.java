package com.shortlink.redirect.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.shortlink.common.cache.LocalCaches;
import com.shortlink.redirect.cache.LinkCache;
import com.shortlink.redirect.cache.LinkSnapshot;
import com.shortlink.redirect.cache.RedisLinkCache;
import com.shortlink.redirect.cache.TwoLevelLinkCache;
import com.shortlink.redirect.metrics.RedirectMetrics;
import com.shortlink.redirect.service.LinkResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * 跳转服务装配。
 *
 * <p>L1 是否启用由配置决定，而不是用 {@code @ConditionalOnProperty} 搞两个 Bean：
 * 只有一个实现类，开关放在这里一目了然，也方便压测时现场切换。</p>
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
    public LinkResolver linkResolver(LinkCache linkCache, RedirectMetrics metrics) {
        return new LinkResolver(linkCache, metrics);
    }
}
