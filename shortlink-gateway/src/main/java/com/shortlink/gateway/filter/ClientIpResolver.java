package com.shortlink.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

/**
 * 解析客户端真实 IP。
 *
 * <p><b>默认不信任 X-Forwarded-For</b>，原因：只有"网关前面确实有一层可信代理"时，
 * XFF 才是可信的。如果网关直接暴露在公网（本地 docker-compose 就是这样），
 * 客户端可以随手写一个 {@code X-Forwarded-For: 1.2.3.4}，于是：</p>
 * <ul>
 *   <li>限流按伪造 IP 计数 → 换个头就绕过；</li>
 *   <li>黑名单按伪造 IP 判定 → 被封的 IP 改个头就能继续访问。</li>
 * </ul>
 *
 * <p>接入真实 LB / Ingress 之后，把 {@code shortlink.gateway.trust-forwarded-for} 置为 true。</p>
 */
@Component
public class ClientIpResolver {

    private static final String XFF = "X-Forwarded-For";

    private final boolean trustForwardedFor;

    public ClientIpResolver(@Value("${shortlink.gateway.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public String resolve(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        if (trustForwardedFor) {
            String forwarded = request.getHeaders().getFirst(XFF);
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }
}
