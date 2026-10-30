package com.shortlink.common.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTest {

    private static final String SECRET = "shortlink-test-secret-0123456789-abcdefghijklmn";
    private static final JwtProperties PROPS =
            new JwtProperties(SECRET, "shortlink", Duration.ofHours(2));

    @Test
    @DisplayName("签发的 token 能被验回同样的身份")
    void issueAndVerify() {
        JwtIssuer issuer = new JwtIssuer(PROPS);
        JwtVerifier verifier = new JwtVerifier(PROPS);

        String token = issuer.issue(42L, "alice");
        Optional<JwtPrincipal> principal = verifier.verify(token);

        assertTrue(principal.isPresent());
        assertEquals(42L, principal.get().userId());
        assertEquals("alice", principal.get().username());
    }

    @Test
    @DisplayName("被篡改的 token 必须被拒绝")
    void rejectsTamperedToken() {
        String token = new JwtIssuer(PROPS).issue(1L, "bob");
        // 改掉签名最后一位
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertFalse(new JwtVerifier(PROPS).verify(tampered).isPresent());
    }

    @Test
    @DisplayName("换一个密钥签的 token 必须被拒绝")
    void rejectsTokenFromAnotherKey() {
        JwtProperties other = new JwtProperties(
                "another-secret-0123456789-0123456789-abcdef", "shortlink", Duration.ofHours(2));
        String token = new JwtIssuer(other).issue(1L, "mallory");

        assertFalse(new JwtVerifier(PROPS).verify(token).isPresent());
    }

    @Test
    @DisplayName("issuer 不匹配必须被拒绝")
    void rejectsWrongIssuer() {
        JwtProperties other = new JwtProperties(SECRET, "other-system", Duration.ofHours(2));
        String token = new JwtIssuer(other).issue(1L, "eve");

        assertFalse(new JwtVerifier(PROPS).verify(token).isPresent());
    }

    @Test
    @DisplayName("过期 token 必须被拒绝")
    void rejectsExpiredToken() {
        JwtIssuer issuer = new JwtIssuer(new JwtProperties(SECRET, "shortlink", Duration.ofSeconds(1)));
        String token = issuer.issue(1L, "old", Instant.now().minus(Duration.ofMinutes(10)));

        assertFalse(new JwtVerifier(PROPS).verify(token).isPresent());
    }

    @Test
    @DisplayName("空 / 乱串 / null 一律拒绝，不抛异常")
    void rejectsGarbage() {
        JwtVerifier verifier = new JwtVerifier(PROPS);
        assertFalse(verifier.verify(null).isPresent());
        assertFalse(verifier.verify("").isPresent());
        assertFalse(verifier.verify("not-a-jwt").isPresent());
        assertFalse(verifier.verify("a.b.c").isPresent());
    }

    @Test
    @DisplayName("密钥太短必须启动失败，而不是悄悄用弱密钥")
    void rejectsWeakSecret() {
        JwtProperties weak = new JwtProperties("short", "shortlink", Duration.ofHours(1));
        assertThrows(IllegalStateException.class, () -> new JwtIssuer(weak));
        assertThrows(IllegalStateException.class, () -> new JwtVerifier(weak));
    }
}
