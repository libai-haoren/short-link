package com.shortlink.redirect.web;

import com.shortlink.component.codec.ShortCodeCodec;
import com.shortlink.component.ratelimit.LocalRateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 跳转面的本地限流。
 *
 * <p>两个桶，目标完全不同：</p>
 * <ul>
 *   <li><b>实例总入口</b>：保护本机容量，挡住超过处理能力的洪峰；</li>
 *   <li><b>单短码</b>：热点保护。一个爆款链接被打到几十万 QPS 时，
 *       即使 L1 命中也会把 CPU 打满，按码限流能让它降级而不是拖垮整台机器。</li>
 * </ul>
 *
 * <p><b>为什么不做 IP 维度</b>：redirect 直接面向公网，前面没有可信代理时只能拿到
 * remote address；按 IP 限流意味着共享出口 IP 的用户会互相影响（公司/学校 NAT 后面几十人共用一个 IP）。
 * IP 维度的防刷放在网关做更合适——那里本来就是管理面的入口。这个取舍在
 * {@link com.shortlink.redirect.web.IpBlacklistWebFilter} 里有同样的说明。</p>
 *
 * <p>只对"形如短码"的路径生效：{@code /ping}、{@code /actuator/**} 不受限，
 * 否则健康检查会把配额吃掉，甚至让探针失败导致容器被重启。</p>
 */
public class LocalRateLimitWebFilter implements WebFilter, Ordered {

    private final LocalRateLimiter globalLimiter;
    private final LocalRateLimiter perCodeLimiter;
    private final Counter rejectedCounter;

    public LocalRateLimitWebFilter(LocalRateLimiter globalLimiter,
                                   LocalRateLimiter perCodeLimiter,
                                   MeterRegistry meterRegistry) {
        this.globalLimiter = globalLimiter;
        this.perCodeLimiter = perCodeLimiter;
        this.rejectedCounter = Counter.builder("shortlink.redirect.ratelimit.rejected")
                .description("被本地限流拒绝的跳转请求数")
                .register(meterRegistry);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String shortCode = extractShortCode(exchange.getRequest().getPath().value());
        if (shortCode == null) {
            return chain.filter(exchange);
        }
        // 先判全局再判单码：全局桶只有一个 key，几乎不可能有竞争
        if (!globalLimiter.tryAcquire("instance", 1) || !perCodeLimiter.tryAcquire(shortCode, 1)) {
            rejectedCounter.increment();
            exchange.getResponse().getHeaders().set("Retry-After", "1");
            return HtmlResponses.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                    ErrorPage.renderTooManyRequests());
        }
        return chain.filter(exchange);
    }

    /**
     * 从路径里取出短码；不是短码形状就返回 null（放行）。
     *
     * <p>长度判断放在最前面：绝大多出请求会在这里被判定为"非短码"，
     * 省掉正则/遍历的全部开销。</p>
     */
    private static String extractShortCode(String path) {
        if (path == null || path.length() != ShortCodeCodec.CODE_LENGTH + 1 || path.charAt(0) != '/') {
            return null;
        }
        String code = path.substring(1);
        return ShortCodeCodec.isValidFormat(code) ? code : null;
    }

    @Override
    public int getOrder() {
        // 黑名单(+10) 之后、业务处理之前
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
