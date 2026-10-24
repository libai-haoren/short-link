package com.shortlink.common.webflux;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Reactive 栈的自动装配。
 *
 * <p>与 common-web 用 {@code @ConditionalOnWebApplication(SERVLET)} 互斥：
 * 同一个库不能同时装配两种栈，否则会出现“测到的和线上的不是一套”的诡异问题。</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class CommonWebfluxAutoConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public TraceIdWebFilter traceIdWebFilter() {
        return new TraceIdWebFilter();
    }

    @Bean
    public GlobalWebExceptionHandler globalWebExceptionHandler() {
        return new GlobalWebExceptionHandler();
    }
}
