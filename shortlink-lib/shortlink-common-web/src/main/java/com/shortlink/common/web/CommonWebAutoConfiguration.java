package com.shortlink.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Servlet 栈的自动装配。
 *
 * <p>库里的 {@code @Component} 默认不会被业务服务扫描到（包路径不同），
 * 所以统一走 Spring Boot 3 的 {@code AutoConfiguration.imports} 注册。
 * 这正是“公共库”和“业务代码”的分界：公共库靠自动装配生效，不要求调用方加 scanBasePackages。</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonWebAutoConfiguration {

    /**
     * 注意这里 new 而不是注入：Filter 若同时是 Bean 又被 FilterRegistrationBean 注册，
     * Boot 可能重复注册，导致 traceId 被生成两次（外层覆盖内层，链路出现断点）。
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration =
                new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setName("traceIdFilter");
        return registration;
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
