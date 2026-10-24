package com.shortlink.common.webflux;

import com.shortlink.common.core.constant.HeaderConstants;
import com.shortlink.common.core.trace.TraceContext;
import com.shortlink.common.core.util.Ulid;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * TraceId WebFilter（WebFlux）。
 *
 * <p>不能有 ThreadLocal：一个请求可能在多个线程上执行，ThreadLocal 会丢。
 * 这里写进 exchange attribute，控制器/异常处理器从 attribute 取。</p>
 *
 * <p>性能注意：本过滤器必须是纯 CPU 操作（读 header、生成 ULID），
 * 一旦在这里做 Redis/DB 调用，整条响应式链路就被阻塞了——这是 WebFlux 最常见的埋雷方式。</p>
 */
public class TraceIdWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(HeaderConstants.TRACE_ID);
        if (!StringUtils.hasText(traceId)) {
            traceId = Ulid.next();
        }
        exchange.getAttributes().put(TraceContext.ATTRIBUTE, traceId);
        exchange.getResponse().getHeaders().set(HeaderConstants.TRACE_ID, traceId);
        return chain.filter(exchange);
    }
}
