package com.shortlink.stats.consumer.support;

import com.shortlink.common.cache.CacheKeys;
import com.shortlink.stats.consumer.service.RealtimeCounter;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Redis 实时计数：PV 用 INCR，UV 用 HyperLogLog。
 *
 * <p><b>为什么 UV 用 HLL 而不是 Set</b>：Set 存的是精确值，但一个日活百万的短链，
 * 一天就是一个百万级元素的 Set（几十 MB），乘以天数直接打爆内存。
 * HLL 固定 12KB，误差约 0.81%，用来做实时看板完全够——
 * 精确 UV 由 ClickHouse 的 {@code uniqExact} 在 T+1 回填。</p>
 */
public class RedisRealtimeCounter implements RealtimeCounter {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 保留 3 天：覆盖"跨天看昨天"的场景，再久的查询走 MySQL/ClickHouse。 */
    private static final Duration TTL = Duration.ofDays(3);

    private final StringRedisTemplate redisTemplate;

    public RedisRealtimeCounter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void increment(String shortCode, long eventTimeMillis, long ipHash) {
        String date = LocalDate.ofInstant(Instant.ofEpochMilli(eventTimeMillis), ZONE).toString();
        // key 由 CacheKeys 统一生成：stats-api 读的必须和这里写的完全一致
        String pvKey = CacheKeys.statsPv(shortCode, date);
        String uvKey = CacheKeys.statsUv(shortCode, date);

        Long pv = redisTemplate.opsForValue().increment(pvKey);
        if (pv != null && pv == 1L) {
            redisTemplate.expire(pvKey, TTL);
        }
        redisTemplate.opsForHyperLogLog().add(uvKey, String.valueOf(ipHash));
        // PFADD 每次都要保证有过期时间：HLL 的 key 第一次创建时就该带上 TTL
        if (pv != null && pv == 1L) {
            redisTemplate.expire(uvKey, TTL);
        }
    }
}
