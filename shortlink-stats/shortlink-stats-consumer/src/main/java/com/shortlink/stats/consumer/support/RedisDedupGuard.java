package com.shortlink.stats.consumer.support;

import com.shortlink.stats.consumer.service.DedupGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 基于 Redis SETNX 的去重。
 *
 * <p>TTL 取 1 小时：比 Kafka 的最长重投窗口（默认 7 天）短得多，
 * 但足够挡住"进程重启导致的近端重投"。真要有跨天的重复，ClickHouse 侧还有
 * {@code ReplacingMergeTree} / 幂等写入兜底。</p>
 *
 * <p>Redis 故障时<b>选择放行</b>（当作第一次见到）：宁可 PV 偏高，也不要整条消费链路停摆。</p>
 */
public class RedisDedupGuard implements DedupGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisDedupGuard.class);

    private static final Duration TTL = Duration.ofHours(1);
    private static final String PREFIX = "sl:dedup:click:";

    private final StringRedisTemplate redisTemplate;

    public RedisDedupGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean firstSeen(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(PREFIX + eventId, "1", TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    @Override
    public void forget(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(PREFIX + eventId);
        } catch (RuntimeException ex) {
            // 撤不掉就算了：重投的消息会被判重跳过，这一条数据丢失。
            // 极端情况下的取舍，记日志以便排查。
            log.warn("forget dedup key failed, eventId={}: {}", eventId, ex.toString());
        }
    }
}
