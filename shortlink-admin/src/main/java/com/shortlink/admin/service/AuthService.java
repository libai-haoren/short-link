package com.shortlink.admin.service;

import com.shortlink.admin.config.AuthProperties;
import com.shortlink.common.core.exception.BizException;
import com.shortlink.common.core.result.ErrorCode;
import com.shortlink.common.jwt.JwtIssuer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 登录（V4 占位实现）。
 *
 * <p>只校验一个内置账号。口令比对用常量时间比较（{@link MessageDigest#isEqual}），
 * 避免通过响应时间差猜口令——虽然这里只有单账号，习惯要在。</p>
 */
@Service
public class AuthService {

    private final AuthProperties properties;
    private final JwtIssuer jwtIssuer;

    public AuthService(AuthProperties properties, JwtIssuer jwtIssuer) {
        this.properties = properties;
        this.jwtIssuer = jwtIssuer;
    }

    /**
     * 校验账号口令并签发 token。
     *
     * <p>失败统一抛 {@link ErrorCode#UNAUTHORIZED}，不区分"用户不存在"与"密码错误"——
     * 区分了等于告诉攻击者账号是否存在。</p>
     */
    public LoginResult login(String username, String password) {
        if (username == null || password == null
                || !constantTimeEquals(username, properties.username())
                || !constantTimeEquals(sha256Hex(password), properties.passwordSha256())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        String token = jwtIssuer.issue(properties.userId(), properties.username());
        return new LoginResult(token, jwtIssuer.ttlSeconds());
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必备算法，走不到这里
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * @param token     JWT
     * @param expiresIn 有效期（秒）
     */
    public record LoginResult(String token, long expiresIn) {
    }
}
