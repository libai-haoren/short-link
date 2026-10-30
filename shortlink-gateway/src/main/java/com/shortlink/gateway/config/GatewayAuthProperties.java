package com.shortlink.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 网关鉴权配置。
 *
 * <p>白名单<b>只放行登录</b>。写成 {@code /api/v1/auth/**} 会把 {@code /auth/me} 一起放行，
 * 而那个接口恰恰要靠网关注入 {@code X-User-Id}——放行后它永远拿不到身份。</p>
 *
 * @param enabled   关掉后所有请求直接放行，用于排查"到底是鉴权拦的还是下游报的"
 * @param whitelist 不需要 token 的路径（Ant 风格）
 */
@ConfigurationProperties(prefix = "shortlink.gateway.auth")
public record GatewayAuthProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue({"/api/v1/auth/login", "/actuator/**"}) String[] whitelist) {
}
