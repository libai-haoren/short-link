package com.shortlink.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.common.core.result.ErrorCode;
import com.shortlink.common.core.result.Result;
import com.shortlink.common.core.trace.TraceContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关直接返回响应时的统一写出口（401 / 429 等）。
 *
 * <p>网关的过滤器在路由之前执行，抛出的异常不会经过 {@code @RestControllerAdvice}
 * （那只覆盖控制器），所以必须自己写响应体。格式与 admin 的 {@link Result} 保持一致，
 * 前端不需要为"被网关拦住"单独写一套解析。</p>
 */
public final class GatewayResponses {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GatewayResponses() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status,
                                   ErrorCode errorCode, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Result<Void> body = Result.<Void>fail(errorCode, message).withTraceId(traceId(exchange));
        byte[] bytes;
        try {
            bytes = OBJECT_MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException ex) {
            // 序列化一个固定结构失败属于编码错误，直接返回空响应避免把异常抛回过滤器链
            return response.setComplete();
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    public static String traceId(ServerWebExchange exchange) {
        Object attribute = exchange.getAttribute(TraceContext.ATTRIBUTE);
        return attribute == null ? null : attribute.toString();
    }
}
