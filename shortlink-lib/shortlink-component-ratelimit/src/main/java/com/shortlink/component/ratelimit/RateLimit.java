package com.shortlink.component.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流标记。
 *
 * <p>接入层（AOP 拦截器）在 V4 实现；当前先用 {@link LocalRateLimiter} 显式调用，
 * 因为 redirect 的限流点在“解析短码之后、跳转之前”，用注解反而不好表达。</p>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流维度。 */
    Dimension dimension() default Dimension.IP;

    /** 时间窗口内允许的请求数。 */
    int permits() default 100;

    /** 窗口长度（秒）。 */
    int windowSeconds() default 1;

    /** 限流维度枚举。 */
    enum Dimension {
        /** 按客户端 IP。 */
        IP,
        /** 按登录用户。 */
        USER,
        /** 按短码（热点保护）。 */
        CODE,
        /** 按接口全局。 */
        GLOBAL
    }
}
