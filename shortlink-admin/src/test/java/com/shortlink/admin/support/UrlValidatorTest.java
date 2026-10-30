package com.shortlink.admin.support;

import com.shortlink.common.core.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 长链校验的风控规则。
 *
 * <p>这一层的价值全在"拒绝"上，所以测试重点是把各种绕过手法都覆盖到：
 * 短链本质是开放重定向，校验漏一个，服务就会变成钓鱼跳板。</p>
 */
class UrlValidatorTest {

    private static final String SELF_DOMAIN = "s.example.com";
    private final UrlValidator validator = new UrlValidator(SELF_DOMAIN);

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/a/b?x=1",
            "http://example.com",
            "https://sub.example.com/path#frag",
            "HTTPS://EXAMPLE.COM/upper",
            "http://8.8.8.8/dns"
    })
    @DisplayName("合法长链放行")
    void acceptsValidUrls(String url) {
        assertDoesNotThrow(() -> validator.validate(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///etc/passwd",
            "ftp://example.com/file",
            "not-a-url",
            ""
    })
    @DisplayName("协议白名单：只允许 http/https")
    void rejectsIllegalSchemeOrMalformed(String url) {
        assertThrows(BizException.class, () -> validator.validate(url));
    }

    @Test
    @DisplayName("空值拒绝")
    void rejectsNull() {
        assertThrows(BizException.class, () -> validator.validate(null));
        assertThrows(BizException.class, () -> validator.validate("   "));
    }

    @Test
    @DisplayName("超长拒绝")
    void rejectsTooLong() {
        String url = "https://example.com/" + "a".repeat(3000);
        assertThrows(BizException.class, () -> validator.validate(url));
    }

    @Test
    @DisplayName("带 userInfo 的伪装地址拒绝：http://trusted.com@evil.com")
    void rejectsUserInfo() {
        assertThrows(BizException.class, () -> validator.validate("http://trusted.com@evil.com/x"));
    }

    @Test
    @DisplayName("指向短链自身拒绝（防跳转环）")
    void rejectsSelfDomain() {
        assertThrows(BizException.class, () -> validator.validate("https://" + SELF_DOMAIN + "/abc"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080/admin",
            "http://localhost:3306",
            "http://10.0.0.5/",
            "http://172.16.0.1/",
            "http://172.31.255.255/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/",  // 云厂商 metadata
            "http://100.64.0.1/",                        // CGNAT
            "http://0.0.0.0/"
    })
    @DisplayName("内网 / 保留 IPv4 一律拒绝（SSRF）")
    void rejectsPrivateIpv4(String url) {
        assertThrows(BizException.class, () -> validator.validate(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[::1]/",
            "http://[fe80::1]/",
            "http://[fd00::1]/"
    })
    @DisplayName("回环 / 链路本地 / 唯一本地 IPv6 拒绝")
    void rejectsPrivateIpv6(String url) {
        assertThrows(BizException.class, () -> validator.validate(url));
    }

    @Test
    @DisplayName("172.32 不在 172.16/12 段内，应当放行")
    void allowsPublicIpOutsidePrivateRange() {
        assertDoesNotThrow(() -> validator.validate("http://172.32.0.1/"));
    }
}
