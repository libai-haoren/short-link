package com.shortlink.redirect.web;

import com.shortlink.common.cache.IpBlacklist;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 跳转面的 IP 黑名单拦截。
 *
 * <p><b>只认 remote address，不看 X-Forwarded-For</b>。redirect 直接暴露给用户，
 * 前面没有可信代理时信任 XFF 等于把黑名单交给攻击者控制——改个头就能绕过封禁。
 * 真有 LB 之后，再引入"可信代理白名单 + 只取最右侧一跳"的解析逻辑。</p>
 *
 * <p>判定是纯内存 {@code Set#contains}，所以可以放在事件循环上，不用切线程。</p>
 */
public class IpBlacklistWebFilter implements WebFilter, Ordered {

    private final IpBlacklist blacklist;

    public IpBlacklistWebFilter(IpBlacklist blacklist) {
        this.blacklist = blacklist;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String ip = clientIp(exchange);
        if (blacklist.isBlocked(ip)) {
            return HtmlResponses.write(exchange, HttpStatus.FORBIDDEN, ErrorPage.renderForbidden());
        }
        return chain.filter(exchange);
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        // 紧跟 TraceIdWebFilter（HIGHEST_PRECEDENCE）之后
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
