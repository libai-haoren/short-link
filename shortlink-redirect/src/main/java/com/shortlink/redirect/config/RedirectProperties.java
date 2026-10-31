package com.shortlink.redirect.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 跳转服务配置。
 *
 * <p>用 record + {@code @DefaultValue}：配置项有合理默认值，缺省时不至于启动失败；
 * 同时保持不可变，避免运行期被误改。</p>
 */
@ConfigurationProperties(prefix = "shortlink.redirect")
public record RedirectProperties(L1 l1, CacheSync cacheSync, RateLimit rateLimit, Blacklist blacklist,
                                 Stats stats, ReadyGate readyGate) {

    /** 整个配置块缺失时兜底，避免 NPE。 */
    public RedirectProperties {
        if (l1 == null) {
            l1 = new L1(true, 100_000L, Duration.ofMinutes(10), Duration.ofMinutes(2));
        }
        if (cacheSync == null) {
            cacheSync = new CacheSync(true, Duration.ofSeconds(1));
        }
        if (rateLimit == null) {
            rateLimit = new RateLimit(true, 100_000L, 500L, Duration.ofSeconds(1));
        }
        if (blacklist == null) {
            blacklist = new Blacklist(true, Duration.ofSeconds(2));
        }
        if (stats == null) {
            stats = new Stats(false, 8192, 200, "shortlink-dev-salt");
        }
        if (readyGate == null) {
            readyGate = new ReadyGate(true, Duration.ofSeconds(2));
        }
    }

    /**
     * L1 本地缓存配置。
     *
     * @param enabled 压测时用来做"开/关 L1"的对比，生产恒为 true
     * @param maxSize 容量上限；10 万条约 80MB，按堆 1/8 估
     * @param ttl     基础 TTL；改链靠失效通知，TTL 只是最后一道保险
     * @param jitter  TTL 随机抖动上限，防同一批 key 同时失效
     */
    public record L1(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("100000") long maxSize,
            @DefaultValue("10m") Duration ttl,
            @DefaultValue("2m") Duration jitter) {
    }

    /**
     * 缓存同步配置。
     *
     * @param enabled             关掉后既不订阅也不轮询，压测时用来单独衡量失效通道的开销
     * @param versionPollInterval 兜底轮询间隔；Pub/Sub 丢消息时靠它自愈
     */
    public record CacheSync(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1s") Duration versionPollInterval) {
    }

    /**
     * 本地限流。
     *
     * @param enabled         开关
     * @param globalCapacity  实例总入口每秒放行量（保护本机容量）
     * @param perCodeCapacity 单个短码在窗口内的放行量（热点保护）
     * @param perCodeWindow   单码限流的窗口长度
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("100000") long globalCapacity,
            @DefaultValue("500") long perCodeCapacity,
            @DefaultValue("1s") Duration perCodeWindow) {
    }

    /**
     * IP 黑名单。
     *
     * @param enabled         开关
     * @param refreshInterval 快照刷新间隔；拉黑后最长这么久生效
     */
    public record Blacklist(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2s") Duration refreshInterval) {
    }
    public record Stats(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("8192") int queueSize,
            @DefaultValue("200") int batchSize,
            @DefaultValue("shortlink-dev-salt") String ipSalt) {
    }

    /**
     * 缓存就绪闸门。
     *
     * <p>Redis 被清空 / 主从空切时，"未命中"不能当成"链接不存在"——那会让全站短链
     * 静默地返回 404 且没有任何告警。只有 {@code sl:cache:ready} 存在时才判 404。</p>
     *
     * @param enabled       关掉后退化为"永远就绪"（改造前的行为），用于压测与灰度回退
     * @param checkInterval 探测间隔；标志变化后最长这么久生效
     */
    public record ReadyGate(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2s") Duration checkInterval) {
    }
}