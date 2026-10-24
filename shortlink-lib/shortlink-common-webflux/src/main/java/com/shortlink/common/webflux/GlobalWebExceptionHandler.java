package com.shortlink.common.webflux;

import com.shortlink.common.core.exception.BizException;
import com.shortlink.common.core.result.ErrorCode;
import com.shortlink.common.core.result.Result;
import com.shortlink.common.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

/**
 * 全局异常处理（WebFlux）。
 *
 * <p>WebFlux 下 {@code @RestControllerAdvice} 仍然有效（由
 * {@code ExceptionHandlerExceptionResolver} 支持），但只能捕获“控制器方法内”抛出的异常。
 * 路由不存在、编解码失败等发生在更外层，由 Boot 的 {@code DefaultErrorWebExceptionHandler} 处理，
 * 若要统一成 Result 结构需替换它（V4 再做，当前不影响主链路）。</p>
 *
 * <p>与 MVC 版本的关键差异：traceId 不能从 ThreadLocal 读，必须从 exchange attribute 取。</p>
 */
@RestControllerAdvice
public class GlobalWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalWebExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException ex, ServerWebExchange exchange) {
        log.warn("biz exception code={} msg={}", ex.getCode(), ex.getMessage());
        Result<Void> body = Result.<Void>fail(ex.getErrorCode(), ex.getMessage())
                .withTraceId(traceIdOf(exchange));
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException ex,
                                                              ServerWebExchange exchange) {
        Result<Void> body = Result.<Void>fail(ErrorCode.PARAM_INVALID, ex.getMessage())
                .withTraceId(traceIdOf(exchange));
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex, ServerWebExchange exchange) {
        String traceId = traceIdOf(exchange);
        log.error("unexpected exception, traceId={}", traceId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.<Void>fail(ErrorCode.SYSTEM_ERROR).withTraceId(traceId));
    }

    private static String traceIdOf(ServerWebExchange exchange) {
        Object attr = exchange.getAttribute(TraceContext.ATTRIBUTE);
        return attr == null ? null : attr.toString();
    }
}
