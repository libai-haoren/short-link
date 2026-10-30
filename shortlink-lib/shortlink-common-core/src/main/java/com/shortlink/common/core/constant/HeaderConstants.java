package com.shortlink.common.core.constant;

/**
 * 跨服务透传的 HTTP / MQ Header 常量。
 *
 * <p>用常量而不是字面量，保证网关、admin、redirect 三处的头名不会写歪——
 * 这是链路追踪能串起来的前提。</p>
 */
public final class HeaderConstants {

    /** 链路 ID，网关生成，全链路透传（含 Kafka header）。 */
    public static final String TRACE_ID = "X-Trace-Id";

    /** 网关鉴权后透传的用户 ID，下游服务不再重复解析 JWT。 */
    public static final String USER_ID = "X-User-Id";

    /** 幂等键，写接口使用。 */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /**
     * 网关解析出的客户端真实 IP，透传给下游用于日志与风控。
     *
     * <p>与 {@code X-Forwarded-For} 的区别：XFF 是客户端可写的外部头，
     * 这个是网关<b>覆盖写入</b>的，下游可以信任。</p>
     */
    public static final String CLIENT_IP = "X-Client-Ip";

    private HeaderConstants() {
    }
}
