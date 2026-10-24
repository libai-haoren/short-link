package com.shortlink.redirect.service;

/**
 * 解析结果：跳转链路的所有可能出口。
 *
 * <p>用结果对象而不是抛异常，原因有二：</p>
 * <ul>
 *   <li>异常要构造堆栈，在 10w QPS 的热路径上是纯浪费；</li>
 *   <li>"链接不存在"是正常业务分支，不是异常，用异常表达会让日志里全是 noise。</li>
 * </ul>
 *
 * @param outcome  结果类型
 * @param location FOUND 时的跳转地址，其余为 null
 */
public record ResolveResult(Outcome outcome, String location) {

    public enum Outcome {
        /** 可以跳转 */
        FOUND,
        /** 短码格式非法，未查询任何缓存 */
        INVALID,
        /** 短码不存在 */
        NOT_FOUND,
        /** 被停用 */
        DISABLED,
        /** 已过期 */
        EXPIRED,
        /** 依赖不可用或数据损坏 */
        UNAVAILABLE
    }

    public static ResolveResult found(String location) {
        return new ResolveResult(Outcome.FOUND, location);
    }

    public static ResolveResult invalid() {
        return new ResolveResult(Outcome.INVALID, null);
    }

    public static ResolveResult notFound() {
        return new ResolveResult(Outcome.NOT_FOUND, null);
    }

    public static ResolveResult disabled() {
        return new ResolveResult(Outcome.DISABLED, null);
    }

    public static ResolveResult expired() {
        return new ResolveResult(Outcome.EXPIRED, null);
    }

    public static ResolveResult unavailable() {
        return new ResolveResult(Outcome.UNAVAILABLE, null);
    }

    public boolean isFound() {
        return outcome == Outcome.FOUND;
    }
}
