package com.shortlink.job.service;

import com.shortlink.common.cache.CacheChannels;
import com.shortlink.common.cache.CacheKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 通过 Redis 失效缓存，并广播给 redirect 清 L1。
 *
 * <p>两步都要做：只删 Redis 主缓存的话，redirect 的本地缓存还会继续命中，
 * 用户依然能访问到已经清理掉的短链，直到 L1 的 TTL 到期（默认 10 分钟）。</p>
 */
public class RedisCacheEvictor implements CacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheEvictor.class);

    private final StringRedisTemplate redisTemplate;

    public RedisCacheEvictor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void evict(String shortCode) {
        if (shortCode == null || shortCode.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(CacheKeys.link(shortCode));
            redisTemplate.convertAndSend(CacheChannels.LINK_EVICT, shortCode);
        } catch (RuntimeException ex) {
            // 失效失败不影响清理本身：数据已软删，缓存最坏等 TTL 自然过期
            log.warn("evict cache failed, code={}: {}", shortCode, ex.toString());
        }
    }
}
