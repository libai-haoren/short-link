package com.shortlink.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 网关限流配置。
 *
 * <p>按客户端 IP 做固定窗口计数。之所以不用令牌桶：网关这一层要的是"挡住明显异常的洪峰"，
 * 固定窗口实现最简单、一次 Redis 调用即可；允许边界双倍突发的缺点在 600/分钟的量级下无所谓。</p>
 *
 * @param enabled  开关
 * @param capacity 每个窗口允许的请求数
 * @param window   窗口长度
 */
@ConfigurationProperties(prefix = "shortlink.gateway.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("600") int capacity,
        @DefaultValue("60s") Duration window) {
}
