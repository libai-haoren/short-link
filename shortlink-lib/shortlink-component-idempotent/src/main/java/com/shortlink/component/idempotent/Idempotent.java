package com.shortlink.component.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等标记。
 *
 * <p>两种用法：</p>
 * <ul>
 *   <li>放在方法上，表示该方法需要幂等保护（配合 AOP 拦截，V4 实现）；</li>
 *   <li>仅作为文档标记，业务代码里直接调用 {@link IdempotentGuard}。</li>
 * </ul>
 *
 * <p>当前骨架阶段采用后者：显式调用比隐式 AOP 更容易调试，
 * 且能保证“幂等检查”和“业务事务”的先后顺序可控。</p>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 幂等键的 SpEL 表达式（AOP 模式使用），为空则取请求头 Idempotency-Key。 */
    String key() default "";

    /** 结果保留时长（秒），期间重复请求直接返回首次结果。 */
    int ttlSeconds() default 86400;

    /** 是否缓存首次结果。false 时只防并发重复提交，不返回旧结果。 */
    boolean cacheResult() default true;
}
