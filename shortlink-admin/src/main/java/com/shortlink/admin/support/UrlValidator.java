package com.shortlink.admin.support;

import com.shortlink.common.core.exception.BizException;
import com.shortlink.common.core.result.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * 长链校验（风控第一道）。
 *
 * <p>为什么必须在创建时校验：短链本质是<b>开放重定向</b>，天然会被用来做钓鱼、
 * 垃圾推广、绕过内网访问。不校验的短链服务上线一周就会被滥用。</p>
 *
 * <p>V1 覆盖：协议白名单、长度、用户信息、自跳转（防环）、内网 IP 字面量。</p>
 *
 * <p><b>V1 未覆盖（V4 补）</b>：域名黑名单、DNS 解析后的真实 IP 校验（TOCTOU 问题）、
 * 重定向链跟随校验、第三方威胁情报。当前只挡 IP 字面量，攻击者用域名指向内网仍然能绕，
 * 这是已知缺口，不要误以为已经安全。</p>
 */
@Component
public class UrlValidator {

    private static final int MAX_LENGTH = 2048;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final String selfDomain;

    public UrlValidator(@Value("${shortlink.short-domain:s.example.com}") String selfDomain) {
        this.selfDomain = selfDomain;
    }

    public void validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BizException(ErrorCode.URL_ILLEGAL, "长链不能为空");
        }
        String url = rawUrl.trim();
        if (url.length() > MAX_LENGTH) {
            throw new BizException(ErrorCode.URL_ILLEGAL, "长链长度不能超过 " + MAX_LENGTH);
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw new BizException(ErrorCode.URL_ILLEGAL, "长链格式非法");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            // 拦掉 javascript: / data: / file: —— 这些是 XSS 与本地文件读取的入口
            throw new BizException(ErrorCode.URL_ILLEGAL, "只支持 http/https 协议");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BizException(ErrorCode.URL_ILLEGAL, "长链缺少合法域名");
        }

        if (uri.getUserInfo() != null) {
            // http://trusted.com@evil.com 这类伪装必须拒绝
            throw new BizException(ErrorCode.URL_ILLEGAL, "长链不允许携带用户信息");
        }

        if (host.equalsIgnoreCase(selfDomain)) {
            throw new BizException(ErrorCode.URL_ILLEGAL, "长链不能指向短链自身，会造成跳转环");
        }

        if (isBlockedHost(host)) {
            throw new BizException(ErrorCode.URL_ILLEGAL, "长链指向内网或保留地址，已拒绝");
        }
    }

    private boolean isBlockedHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);

        // IPv6 字面量在 URI 里带方括号，如 [::1]
        if (host.startsWith("[")) {
            host = host.substring(1, host.length() - 1);
            return isBlockedIpv6(host);
        }

        if (host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")) {
            return true;
        }

        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return isBlockedIpv4(host);
        }

        if (host.contains(":")) {
            return isBlockedIpv6(host);
        }

        // 普通域名：V1 只能放行，真实解析交给 V4
        return false;
    }

    private static boolean isBlockedIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octet = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octet[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                return false;
            }
            if (octet[i] < 0 || octet[i] > 255) {
                return false;
            }
        }
        int a = octet[0];
        int b = octet[1];
        return a == 0                                   // 0.0.0.0/8
                || a == 10                              // 10/8
                || a == 127                             // 回环
                || (a == 100 && b >= 64 && b <= 127)    // 100.64/10 CGNAT
                || (a == 169 && b == 254)               // 链路本地 + 云厂商 metadata
                || (a == 172 && b >= 16 && b <= 31)     // 172.16/12
                || (a == 192 && b == 168)               // 192.168/16
                || a >= 224;                            // 组播 / 保留
    }

    private static boolean isBlockedIpv6(String host) {
        return host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1")
                || host.startsWith("fe80")              // 链路本地
                || host.startsWith("fc")                // 唯一本地地址 fc00::/7
                || host.startsWith("fd")
                || host.startsWith("::ffff:127.");      // IPv4 映射的回环
    }
}
