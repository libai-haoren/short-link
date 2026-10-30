package com.shortlink.redirect.web;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * HTML 错误响应的统一出口。
 *
 * <p>控制器和两个 WebFilter（黑名单、限流）都要写错误页，把响应头规则收在一处：
 * 漏了 {@code no-store} 会把 404 缓存到浏览器，漏了 {@code nosniff} 是白送的 MIME 嗅探风险。</p>
 */
public final class HtmlResponses {

    private static final MediaType HTML_UTF8 = MediaType.valueOf("text/html;charset=UTF-8");

    private HtmlResponses() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, byte[] body) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        var headers = response.getHeaders();
        headers.setContentType(HTML_UTF8);
        headers.setContentLength(body.length);
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
