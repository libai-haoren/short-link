package com.shortlink.gateway.filter;

import com.shortlink.common.core.constant.HeaderConstants;
import com.shortlink.common.core.result.ErrorCode;
import com.shortlink.common.jwt.JwtPrincipal;
import com.shortlink.common.jwt.JwtVerifier;
import com.shortlink.gateway.config.GatewayAuthProperties;
import com.shortlink.gateway.web.GatewayResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * JWT 鉴权过滤器。
 *
 * <p>做三件事，缺一不可：</p>
 * <ol>
 *   <li><b>剥离客户端伪造的身份头</b>：{@code X-User-Id} 必须由网关写入，
 *       如果客户端自己带一个进来，下游会直接信任它——这是最典型的越权漏洞；</li>
 *   <li>校验 token，失败返回 401；</li>
 *   <li>把身份写进请求头传给下游，下游不再重复解析 JWT。</li>
 * </ol>
 *
 * <p>为什么在网关做而不是每个服务都做：解析逻辑只有一份，不会出现"admin 校验了、
 * stats 忘了校验"这种不一致。代价是网关成为信任边界，必须保证它不可绕过
 * （生产上 admin 不应对公网暴露，只允许网关访问）。</p>
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthGlobalFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String USERNAME_HEADER = "X-Username";

    private final JwtVerifier verifier;
    private final GatewayAuthProperties properties;
    private final ClientIpResolver ipResolver;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthGlobalFilter(JwtVerifier verifier,
                               GatewayAuthProperties properties,
                               ClientIpResolver ipResolver) {
        this.verifier = verifier;
        this.properties = properties;
        this.ipResolver = ipResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 无论走哪条分支，先把客户端可能伪造的身份头摘掉
        ServerWebExchange sanitized = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(headers -> {
                            headers.remove(HeaderConstants.USER_ID);
                            headers.remove(USERNAME_HEADER);
                            headers.remove(HeaderConstants.CLIENT_IP);
                        })
                        .build())
                .build();

        if (!properties.enabled() || isWhitelisted(sanitized)) {
            return chain.filter(sanitized);
        }

        String authorization = sanitized.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return reject(sanitized, "缺少 Authorization: Bearer token");
        }

        Optional<JwtPrincipal> principal = verifier.verify(authorization.substring(BEARER_PREFIX.length()).trim());
        if (principal.isEmpty()) {
            return reject(sanitized, "token 无效或已过期");
        }

        ServerHttpRequest authenticated = sanitized.getRequest().mutate()
                .header(HeaderConstants.USER_ID, String.valueOf(principal.get().userId()))
                .header(USERNAME_HEADER, principal.get().username())
                // 客户端真实 IP 由网关写入，下游可以信任（用于日志与风控）
                .header(HeaderConstants.CLIENT_IP, ipResolver.resolve(sanitized))
                .build();
        return chain.filter(sanitized.mutate().request(authenticated).build());
    }

    private Mono<Void> reject(ServerWebExchange exchange, String reason) {
        // 不把 reason 回给客户端：区分"没带 token"和"token 过期"对攻击者是有用信息
        log.debug("reject request {} {}: {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(), reason);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return GatewayResponses.write(exchange, HttpStatus.UNAUTHORIZED,
                ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.message());
    }

    private boolean isWhitelisted(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        for (String pattern : properties.whitelist()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        // 在限流之后：让限流先挡住洪峰，再做成本更高的验签
        return -90;
    }
}
