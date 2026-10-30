package com.shortlink.common.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * JWT 校验。
 *
 * <p>校验四件事：<b>签名</b>（防篡改）、<b>issuer</b>（防止别的系统的 token 混进来）、
 * <b>过期时间</b>（jjwt 自动检查）、<b>uid 字段存在</b>。</p>
 *
 * <p>失败一律返回 {@link Optional#empty()}，不抛异常也不区分原因：
 * 对调用方来说"token 不可用"就够，把"签名错"和"已过期"区分开只会给攻击者提供信息。
 * 需要排障时看网关日志里的具体异常类型。</p>
 */
public class JwtVerifier {

    private final SecretKey key;
    private final String issuer;

    public JwtVerifier(JwtProperties properties) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "shortlink.jwt.secret 至少需要 32 字节（HS256 要求 256 bit），当前 " + secret.length);
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.issuer = properties.issuer();
    }

    public Optional<JwtPrincipal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Number uid = toNumber(claims.get("uid"));
            if (uid == null) {
                return Optional.empty();
            }
            String username = claims.get("uname") == null ? "" : String.valueOf(claims.get("uname"));
            return Optional.of(new JwtPrincipal(uid.longValue(), username));
        } catch (JwtException | IllegalArgumentException ex) {
            // 签名不对 / 过期 / 结构非法 / issuer 不匹配，全部走这里
            return Optional.empty();
        }
    }

    /** Jackson 反序列化小整数时会变成 Integer，直接按 Long 取会 ClassCastException。 */
    private static Number toNumber(Object raw) {
        if (raw instanceof Number number) {
            return number;
        }
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
