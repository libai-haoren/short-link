package com.shortlink.gateway.config;

import com.shortlink.common.cache.IpBlacklist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;

/**
 * 网关配置装配。
 *
 * <p>两个 {@code @ConfigurationProperties} 都用 record + {@code @DefaultValue}，
 * 配置缺省时也有安全默认值（鉴权开、600 次/分钟）。</p>
 */
@Configuration
@EnableConfigurationProperties({GatewayAuthProperties.class, RateLimitProperties.class})
public class GatewayConfig {

    /**
     * IP 黑名单：每 2 秒从 Redis 拉一次全量快照。
     *
     * <p>不按请求查 Redis，是因为黑名单判断发生在每个请求上；
     * 拉全量到内存后判定退化成 {@code Set#contains}，零网络开销。</p>
     */
    @Bean
    public IpBlacklist ipBlacklist(ReactiveStringRedisTemplate redisTemplate,
                                   @Value("${shortlink.gateway.blacklist.refresh-interval:2s}") Duration refreshInterval) {
        return new IpBlacklist(redisTemplate, refreshInterval);
    }
}

