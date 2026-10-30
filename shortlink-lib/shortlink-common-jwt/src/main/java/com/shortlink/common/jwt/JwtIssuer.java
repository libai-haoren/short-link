package com.shortlink.common.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发。
 *
 * <p>只用 HS256：对称密钥，签发方与校验方共享 secret。对单体多服务的场景足够，
 * 而且比 RS256 少一次密钥分发与 RSA 运算。真要做"多个校验方互不信任"时再换 RS256。</p>
 */
public class JwtIssuer {

    private final SecretKey key;
    private final String issuer;
    private final Duration ttl;

    public JwtIssuer(JwtProperties properties) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "shortlink.jwt.secret 至少需要 32 字节（HS256 要求 256 bit），当前 " + secret.length);
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.issuer = properties.issuer();
        this.ttl = properties.ttl();
    }

    public String issue(long userId, String username) {
        return issue(userId, username, Instant.now());
    }

    /** 显式传入时间，便于单测验证过期行为。 */
    public String issue(long userId, String username, Instant now) {
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claim("uid", userId)
                .claim("uname", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}
