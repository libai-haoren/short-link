package com.shortlink.admin.support;

import com.shortlink.admin.domain.entity.ShortLinkDO;
import com.shortlink.common.cache.CacheKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 把短链元数据写入 Redis（redirect 的唯一数据来源）。
 *
 * <p>两个关键决策：</p>
 *
 * <ol>
 *   <li><b>写时更新，不是删除。</b>redirect 连不上 MySQL，如果这里只 DEL，
 *       下一次跳转就会因为查不到而 404。这是短链场景与普通 Cache Aside 最大的区别。</li>
 *   <li><b>TTL 加随机抖动。</b>批量导入的短链 TTL 完全一致，会在同一秒集体失效，
 *       把瞬时流量全打到回源路径上。</li>
 * </ol>
 *
 * <p>字段与 redirect 侧读取的 Hash 结构必须严格一致，改动时两边一起改。</p>
 */
@Component
public class LinkCacheWriter {

    /** 基础 TTL，生产可配置；这里先写死便于 V1 验证。 */
    private static final Duration BASE_TTL = Duration.ofHours(24);

    /** 抖动上限 = 基础 TTL 的 10%。 */
    private static final long JITTER_SECONDS = BASE_TTL.toSeconds() / 10;

    private final StringRedisTemplate redisTemplate;

    public LinkCacheWriter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
    }

    /**
     * 写入（或覆盖）短链缓存。重复调用是安全的。
     */
    public void put(ShortLinkDO link) {
        String key = CacheKeys.link(link.getShortCode());
        Map<String, String> fields = new LinkedHashMap<>(8);
        fields.put("shortCode", link.getShortCode());
        fields.put("longUrl", link.getLongUrl());
        fields.put("status", String.valueOf(link.getStatus() == null ? 1 : link.getStatus()));
        // 0 表示永久；用 epoch millis 避免时区问题
        fields.put("expireTime", link.getExpireTime() == null
                ? "0"
                : String.valueOf(link.getExpireTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        fields.put("maxClick", String.valueOf(link.getMaxClick() == null ? 0L : link.getMaxClick()));
        fields.put("domainId", String.valueOf(link.getDomainId() == null ? 1L : link.getDomainId()));

        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expire(key, jitteredTtl());
    }

    private static Duration jitteredTtl() {
        long extra = JITTER_SECONDS <= 0 ? 0 : ThreadLocalRandom.current().nextLong(JITTER_SECONDS + 1);
        return BASE_TTL.plusSeconds(extra);
    }
}
