package com.shortlink.common.jwt;

/**
 * 从 token 里解出的调用者身份。
 *
 * <p>网关校验通过后会把这两个字段写进请求头 {@code X-User-Id} / {@code X-Username}，
 * 下游服务直接信任，不再重复解析 JWT——"只在一处解析"能避免各服务实现不一致导致的越权。</p>
 *
 * @param userId 用户 ID
 * @param username 用户名，仅用于日志与审计
 */
public record JwtPrincipal(long userId, String username) {
}
