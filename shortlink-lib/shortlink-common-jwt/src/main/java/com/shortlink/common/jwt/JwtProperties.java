package com.shortlink.common.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * JWT 配置。
 *
 * <p>{@code secret} 是 HS256 的共享密钥，必须 ≥ 32 字节（256 bit），否则 jjwt 会直接抛
 * {@code WeakKeyException}——这是它替我们做的一次安全检查，别用短字符串糊弄。</p>
 *
 * @param secret 签名密钥，生产必须从配置中心注入
 * @param issuer 签发者，校验时会强制比对，防止用别人的 token 混进来
 * @param ttl    有效期
 */
@ConfigurationProperties(prefix = "shortlink.jwt")
public record JwtProperties(
        @DefaultValue("shortlink-dev-secret-please-change-in-production-0123456789") String secret,
        @DefaultValue("shortlink") String issuer,
        @DefaultValue("2h") Duration ttl) {
}
