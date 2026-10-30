package com.shortlink.gateway.filter;

import com.shortlink.common.cache.IpBlacklist;
import com.shortlink.common.core.result.ErrorCode;
import com.shortlink.gateway.web.GatewayResponses;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * IP 黑名单过滤器。
 *
 * <p>排在限流之前：判断是纯内存 {@code Set#contains}，比限流那次 Redis 调用还便宜，
 * 而且被封的 IP 不应该再消耗限流配额。</p>
 *
 * <p>黑名单数据由 {@link IpBlacklist} 每 2 秒从 Redis 拉一次快照，
 * 所以拉黑后最长 2 秒生效，不需要重启或改配置。</p>
 */
@Component
public class IpBlacklistGlobalFilter implements GlobalFilter, Ordered {

    private final IpBlacklist blacklist;
    private final ClientIpResolver ipResolver;
    private final Counter blockedCounter;

    public IpBlacklistGlobalFilter(IpBlacklist blacklist,
                                   ClientIpResolver ipResolver,
                                   MeterRegistry meterRegistry) {
        this.blacklist = blacklist;
        this.ipResolver = ipResolver;
        this.blockedCounter = Counter.builder("shortlink.gateway.blacklist.blocked")
                .description("被 IP 黑名单拦截的请求数")
                .register(meterRegistry);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String ip = ipResolver.resolve(exchange);
        if (blacklist.isBlocked(ip)) {
            blockedCounter.increment();
            return GatewayResponses.write(exchange, HttpStatus.FORBIDDEN,
                    ErrorCode.FORBIDDEN, "访问受限");
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -110;
    }
}
