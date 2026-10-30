package com.shortlink.redirect.stats;

import com.shortlink.common.core.trace.TraceContext;
import com.shortlink.common.core.util.IpHasher;
import com.shortlink.common.mq.event.ClickEvent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

/**
 * 从 HTTP 请求构造点击事件。
 *
 * <p>放在单独的类里而不是塞进控制器：这段逻辑有明确的边界条件
 * （IP 缺失、UA 超长、referer 为空），单独拿出来才能写单测。</p>
 */
public final class ClickEvents {

    /** UA / referer 截断长度：Kafka 单条消息有上限，异常长的头会拖垮整个批次。 */
    private static final int MAX_HEADER_LENGTH = 512;

    private ClickEvents() {
    }

    /**
     * @param shortCode 命中的短码（只有真的跳转成功才应该调用本方法）
     * @param salt      IP 哈希盐
     * @param nowMillis 事件时间，显式传入便于测试
     */
    public static ClickEvent build(ServerWebExchange exchange, String shortCode, String salt, long nowMillis) {
        ServerHttpRequest request = exchange.getRequest();
        long ipHash = IpHasher.hash(clientIp(exchange), salt);
        String userAgent = truncate(request.getHeaders().getFirst(HttpHeaders.USER_AGENT));
        String referer = truncate(request.getHeaders().getFirst(HttpHeaders.REFERER));

        // eventId 由"短码 + IP 哈希 + 毫秒"组成：同一个人同一毫秒点同一个短链会被判重，
        // 这个误差在 PV 统计里可以忽略，但它能挡住 Kafka 至少一次投递造成的重复。
        String eventId = shortCode + ':' + ipHash + ':' + nowMillis;

        return new ClickEvent(eventId, shortCode, nowMillis, ipHash, userAgent, referer, TraceContext.get());
    }

    /**
     * 取客户端 IP。
     *
     * <p>只认 remote address，不信任 {@code X-Forwarded-For}：
     * redirect 直接面向公网，信任一个客户端可写的头等于让攻击者决定统计口径。</p>
     */
    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "";
        }
        return remote.getAddress().getHostAddress();
    }

    private static String truncate(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.length() <= MAX_HEADER_LENGTH ? value : value.substring(0, MAX_HEADER_LENGTH);
    }
}
