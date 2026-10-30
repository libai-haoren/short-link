package com.shortlink.admin.support;

import com.shortlink.admin.domain.entity.ShortLinkDO;
import com.shortlink.common.cache.CacheChannels;
import com.shortlink.common.cache.CacheKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 短链缓存写入 + 变更通知。
 *
 * <p>三个关键决策：</p>
 *
 * <ol>
 *   <li><b>写时更新，不是删除。</b>redirect 连不上 MySQL，如果这里只 DEL，
 *       下一次跳转就会因为查不到而 404。这是短链场景与普通 Cache Aside 最大的区别。</li>
 *   <li><b>TTL 加随机抖动。</b>批量导入的短链 TTL 完全一致，会在同一秒集体失效。</li>
 *   <li><b>写完之后必须通知。</b>不通知的话，redirect 的 L1 要等 10 分钟 TTL 才更新。
 *       通知与版本号都收在 {@link #put} 里，调用方不可能漏发。</li>
 * </ol>
 *
 * <p>字段与 redirect 侧读取的 Hash 结构必须严格一致，改动时两边一起改。</p>
 */
@Component
public class LinkCacheWriter {

    private static final Logger log = LoggerFactory.getLogger(LinkCacheWriter.class);

    /** 基础 TTL。改链靠失效通知，TTL 只是最后一道保险。 */
    private static final Duration BASE_TTL = Duration.ofHours(24);

    /** 抖动上限 = 基础 TTL 的 10%。 */
    private static final long JITTER_SECONDS = BASE_TTL.toSeconds() / 10;

    private final StringRedisTemplate redisTemplate;
    private final int writeRetry;
    private final Counter writeFailure;

    public LinkCacheWriter(StringRedisTemplate redisTemplate,
                           @Value("${shortlink.admin.cache.write-retry:3}") int writeRetry,
                           MeterRegistry meterRegistry) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.writeRetry = Math.max(1, writeRetry);
        this.writeFailure = Counter.builder("shortlink.cache.write.failure")
                .description("缓存写入最终失败次数，非零需关注（会导致 redirect 读到旧值）")
                .register(meterRegistry);
    }

    /**
     * 写入缓存 → 递增全局版本号 → 广播失效。重复调用是安全的。
     *
     * <p>写入失败时<b>不</b>发通知：Redis 里还是旧值，通知了也只是让大家一起去读同一份旧值。
     * 失败会打点和告警，靠重建接口恢复。</p>
     */
    public void put(ShortLinkDO link) {
        if (!writeOnly(link)) {
            return;
        }
        markCacheChanged();
        publishEvict(link.getShortCode());
    }

    /**
     * 只写 Redis，不发通知、不动版本号。
     *
     * <p>给重建流程用：重建时会写几万条，逐条广播会把频道打爆。
     * 重建结束后统一发一次 {@link CacheChannels#EVICT_ALL}。</p>
     *
     * @return true 表示写入成功
     */
    public boolean writeOnly(ShortLinkDO link) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= writeRetry; attempt++) {
            try {
                doWrite(link);
                return true;
            } catch (RuntimeException ex) {
                last = ex;
                log.warn("write cache failed, code={}, attempt={}/{}", link.getShortCode(), attempt, writeRetry);
            }
        }
        writeFailure.increment();
        log.error("write cache gave up, code={}, redirect may serve stale value until TTL",
                link.getShortCode(), last);
        return false;
    }

    /** 递增全局版本号，让所有实例（含漏收 Pub/Sub 的）在 1s 内自愈。 */
    public void markCacheChanged() {
        redisTemplate.opsForValue().increment(CacheKeys.CACHE_VERSION);
    }

    /** 广播"这个短码失效了"。 */
    public void publishEvict(String shortCode) {
        redisTemplate.convertAndSend(CacheChannels.LINK_EVICT, shortCode);
    }

    /** 广播"全部失效"（重建后使用）。 */
    public void publishEvictAll() {
        redisTemplate.convertAndSend(CacheChannels.LINK_EVICT, CacheChannels.EVICT_ALL);
    }

    private void doWrite(ShortLinkDO link) {
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
