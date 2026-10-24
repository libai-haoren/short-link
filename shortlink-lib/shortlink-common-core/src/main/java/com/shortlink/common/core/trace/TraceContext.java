package com.shortlink.common.core.trace;

/**
 * 链路 ID 上下文。
 *
 * <p>两种承载方式：</p>
 * <ul>
 *   <li>MVC / 普通线程：{@link ThreadLocal}（由 common-web 的过滤器负责清理，避免线程池串味）。</li>
 *   <li>WebFlux：不能跨线程复用 ThreadLocal，改用 exchange attribute，
 *       键为 {@link #ATTRIBUTE}（见 common-webflux）。</li>
 * </ul>
 */
public final class TraceContext {

    /** WebFlux exchange attribute 的 key。 */
    public static final String ATTRIBUTE = TraceContext.class.getName() + ".traceId";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String get() {
        return HOLDER.get();
    }

    public static void set(String traceId) {
        HOLDER.set(traceId);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
