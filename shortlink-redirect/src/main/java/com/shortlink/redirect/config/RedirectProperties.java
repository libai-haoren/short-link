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
public record RedirectProperties(L1 l1) {

    /** 整个 shortlink.redirect 缺失时兜底，避免 NPE。 */
    public RedirectProperties {
        if (l1 == null) {
            l1 = new L1(true, 100_000L, Duration.ofMinutes(10), Duration.ofMinutes(2));
        }
    }

    /**
     * L1 本地缓存配置。
     *
     * @param enabled 压测时用来做"开/关 L1"的对比，生产恒为 true
     * @param maxSize 容量上限；10 万条约 80MB，按堆 1/8 估
     * @param ttl     基础 TTL；也是"改链后最长多久生效"的上限
     * @param jitter  TTL 随机抖动上限，防同一批 key 同时失效
     */
    public record L1(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("100000") long maxSize,
            @DefaultValue("10m") Duration ttl,
            @DefaultValue("2m") Duration jitter) {
    }
}
