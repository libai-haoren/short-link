package com.shortlink.stats.api.support;

/**
 * Redis 里的实时值。
 *
 * @param pv 当日累计访问（String INCR）
 * @param uv 当日去重访客（HyperLogLog PFCOUNT，估算值）
 */
public record RealtimePoint(long pv, long uv) {
}
