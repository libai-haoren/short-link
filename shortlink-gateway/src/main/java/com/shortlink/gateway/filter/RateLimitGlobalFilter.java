package com.shortlink.gateway.filter;

import com.shortlink.common.core.result.ErrorCode;
import com.shortlink.gateway.config.RateLimitProperties;
import com.shortlink.gateway.web.GatewayResponses;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 按客户端 IP 的固定窗口限流。
 *
 * <p>一次 Lua 脚本完成 INCR + EXPIRE + 判断，避免"先读再写"的竞态：
 * 两个并发请求可能都读到 599 然后都放行。</p>
 *
 * <p><b>固定窗口的固有缺陷</b>：窗口边界处可能通过 2 倍流量（59 秒的 600 次 + 61 秒的 600 次）。
 * 在"挡住刷接口"这个目标下可以接受；要更平滑可以换滑动窗口（内存成本高）或令牌桶。</p>
 *
 * <p><b>Redis 故障时选择放行</b>（fail-open）：网关是唯一的流量入口，
 * 限流组件挂掉不应该让整个系统不可用。代价是故障期间失去保护，用指标告警兜。
 * 反过来（fail-closed）会让一次 Redis 抖动演变成全站 429。</p>
 */
@Component
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitGlobalFilter.class);

    private static final String LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if current > tonumber(ARGV[1]) then
                return 0
            end
            return 1
            """;

    private static final RedisScript<Long> SCRIPT = RedisScript.of(LUA, Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final ClientIpResolver ipResolver;
    private final Counter rejectedCounter;
    private final Counter redisErrorCounter;

    public RateLimitGlobalFilter(ReactiveStringRedisTemplate redisTemplate,
                                 RateLimitProperties properties,
                                 ClientIpResolver ipResolver,
                                 MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.ipResolver = ipResolver;
        this.rejectedCounter = Counter.builder("shortlink.gateway.ratelimit.rejected")
                .description("被限流拒绝的请求数")
                .register(meterRegistry);
        this.redisErrorCounter = Counter.builder("shortlink.gateway.ratelimit.error")
                .description("限流组件自身异常次数（此时放行）")
                .register(meterRegistry);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.enabled()) {
            return chain.filter(exchange);
        }

        String key = buildKey(exchange);
        List<String> args = List.of(
                String.valueOf(properties.capacity()),
                String.valueOf(Math.max(1, properties.window().toSeconds())));

        return redisTemplate.execute(SCRIPT, List.of(key), args)
                .next()
                .defaultIfEmpty(1L)
                .flatMap(allowed -> allowed == 1L
                        ? chain.filter(exchange)
                        : reject(exchange))
                .onErrorResume(ex -> {
                    redisErrorCounter.increment();
                    log.warn("rate limiter unavailable, allowing request: {}", ex.toString());
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        rejectedCounter.increment();
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER,
                String.valueOf(Math.max(1, properties.window().toSeconds())));
        return GatewayResponses.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.message());
    }

    /**
     * key = {@code sl:rate:ip:{ip}:{windowIndex}}。
     *
     * <p>窗口序号而不是时间戳：固定窗口的 key 必须对齐到同一个桶，
     * 否则每次请求都是新 key，限流永远不会触发。</p>
     */
    private String buildKey(ServerWebExchange exchange) {
        long windowSeconds = Math.max(1, properties.window().toSeconds());
        long windowIndex = System.currentTimeMillis() / 1000 / windowSeconds;
        return "sl:rate:ip:" + ipResolver.resolve(exchange) + ":" + windowIndex;
    }

    @Override
    public int getOrder() {
        // 在鉴权之前：未认证的洪水也该被挡住，且一次 Redis 调用比 HMAC 计算便宜
        return -100;
    }
}
