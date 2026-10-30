package com.shortlink.gateway.filter;

import com.shortlink.common.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 访问日志：method / path / status / 耗时 / traceId。
 *
 * <p>用 {@code doFinally} 而不是在过滤器开头打日志：只有链路走完（或被短路）时才知道最终状态码。
 * 过滤器顺序取 {@link Ordered#HIGHEST_PRECEDENCE}，保证它包住限流与鉴权——
 * 否则被限流拒绝的请求不会留下访问记录。</p>
 *
 * <p>只记这五项，不记请求体与响应体：body 可能含长链或敏感参数，
 * 且高 QPS 下会把磁盘打满。</p>
 *
 * <p><b>redirect 不做同样的日志</b>：跳转面 QPS 高出两个数量级，每条请求一行日志会直接成为瓶颈。
 * 那边靠指标（Prometheus）+ 采样日志，见设计文档 5.4。</p>
 */
@Component
public class AccessLogGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccessLogGlobalFilter.class);

    private static final String ACTUATOR_PREFIX = "/actuator";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith(ACTUATOR_PREFIX)) {
            // 健康检查每 10s 一次，记了只是噪音
            return chain.filter(exchange);
        }

        long startNanos = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            Object traceId = exchange.getAttribute(TraceContext.ATTRIBUTE);
            log.info("{} {} -> {} {}ms trace={}",
                    exchange.getRequest().getMethod(),
                    path,
                    status == null ? "-" : status.value(),
                    elapsedMillis,
                    traceId == null ? "-" : traceId);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
