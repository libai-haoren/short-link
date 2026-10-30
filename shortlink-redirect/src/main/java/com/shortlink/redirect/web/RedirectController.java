package com.shortlink.redirect.web;

import com.shortlink.redirect.metrics.RedirectMetrics;
import com.shortlink.redirect.service.LinkResolver;
import com.shortlink.redirect.service.ResolveResult;
import com.shortlink.redirect.stats.ClickTracker;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * 跳转入口。
 *
 * <p>用 {@code @GetMapping("/{code}")} 通配而不是正则约束，是为了让<b>所有</b>非法输入
 * 都走本控制器的 HTML 错误页。若用 {@code {code:[0-9A-Za-z]{7}}}，长度不对的请求会落到
 * WebFlux 默认的 JSON 错误页，浏览器里看就是一段裸 JSON。</p>
 *
 * <p>副作用：{@code /actuator}（单段路径）会被本方法吞掉并返回 404 HTML。
 * 健康检查用两段的 {@code /actuator/health}，不受影响。</p>
 *
 * <p>黑名单与限流在 {@code WebFilter} 里就短路了，走不到这里；
 * 所以本类只关心"解析 → 埋点 → 跳转/错误页"。</p>
 */
@RestController
public class RedirectController {

    private final LinkResolver linkResolver;
    private final RedirectMetrics metrics;
    private final ClickTracker clickTracker;

    public RedirectController(LinkResolver linkResolver, RedirectMetrics metrics, ClickTracker clickTracker) {
        this.linkResolver = linkResolver;
        this.metrics = metrics;
        this.clickTracker = clickTracker;
    }

    @GetMapping("/ping")
    public Mono<String> ping() {
        return Mono.just("pong");
    }

    @GetMapping("/{code}")
    public Mono<Void> redirect(@PathVariable String code, ServerWebExchange exchange) {
        long startNanos = System.nanoTime();
        return linkResolver.resolve(code)
                .flatMap(result -> respond(result, exchange, code))
                .doFinally(signalType -> metrics.recordLatency(System.nanoTime() - startNanos));
    }

    private Mono<Void> respond(ResolveResult result, ServerWebExchange exchange, String shortCode) {
        ResolveResult effective = ensureValidLocation(result);
        metrics.recordOutcome(effective.outcome());
        if (effective.isFound()) {
            // 只有真正要跳转才记一次点击；埋点是入队操作，不会阻塞也不会抛异常
            clickTracker.track(exchange, shortCode);
            return writeRedirect(effective.location(), exchange);
        }
        return HtmlResponses.write(exchange,
                HttpStatus.valueOf(ErrorPage.statusOf(effective.outcome())),
                ErrorPage.render(effective.outcome()));
    }

    /**
     * 缓存里的 longUrl 可能是历史脏数据，{@code URI.create} 会抛 IllegalArgumentException。
     * 在写响应头之前先验证，避免"响应已开始写入才发现地址非法"。
     */
    private static ResolveResult ensureValidLocation(ResolveResult result) {
        if (!result.isFound()) {
            return result;
        }
        try {
            URI.create(result.location());
            return result;
        } catch (IllegalArgumentException ex) {
            return ResolveResult.unavailable();
        }
    }

    private Mono<Void> writeRedirect(String location, ServerWebExchange exchange) {
        var response = exchange.getResponse();
        HttpHeaders headers = response.getHeaders();
        response.setStatusCode(HttpStatus.FOUND);
        headers.setLocation(URI.create(location));
        // 302 还不够：某些代理/浏览器会启发式缓存，必须显式禁止
        headers.setCacheControl("no-store");
        headers.setContentLength(0);
        return response.setComplete();
    }
}
