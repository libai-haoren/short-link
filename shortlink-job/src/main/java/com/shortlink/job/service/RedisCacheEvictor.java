package com.shortlink.job.service;

import com.shortlink.common.cache.CacheChannels;
import com.shortlink.common.cache.CacheKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.List;

/**
 * 通过 Redis 失效缓存，并广播给 redirect 清 L1。
 *
 * <p>两步都要做：只删 Redis 主缓存的话，redirect 的本地缓存还会继续命中，
 * 用户依然能访问到已经清理掉的短链，直到 L1 的 TTL 到期（默认 10 分钟）。</p>
 *
 * <p><b>版本号必须与广播消息 1:1</b>：redirect 的版本对账是拿"版本增量"和
 * "实收消息数"比较的。只发消息不 bump 版本号，这条消息丢了永远发现不了；
 * 只 bump 不发消息，则会被误判成丢消息而触发一次全量失效。</p>
 *
 * <p>批量走一次 pipeline：一次往返发完一批 DEL / INCR / PUBLISH，
 * 既保留精确失效（不牵连无关的 key），又不把命令数放大成 3N 次网络往返。</p>
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
            redisTemplate.opsForValue().increment(CacheKeys.CACHE_VERSION);
            redisTemplate.convertAndSend(CacheChannels.LINK_EVICT, shortCode);
        } catch (RuntimeException ex) {
            // 失效失败不影响清理本身：数据已软删，缓存最坏等 TTL 自然过期
            log.warn("evict cache failed, code={}: {}", shortCode, ex.toString());
        }
    }

    @Override
    public void evictBatch(List<String> shortCodes) {
        if (shortCodes == null || shortCodes.isEmpty()) {
            return;
        }
        RedisSerializer<String> serializer = redisTemplate.getStringSerializer();
        byte[] channel = serializer.serialize(CacheChannels.LINK_EVICT);
        byte[] versionKey = serializer.serialize(CacheKeys.CACHE_VERSION);

        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String shortCode : shortCodes) {
                    if (shortCode == null || shortCode.isBlank()) {
                        continue;
                    }
                    connection.del(serializer.serialize(CacheKeys.link(shortCode)));
                    connection.incr(versionKey);
                    connection.publish(channel, serializer.serialize(shortCode));
                }
                return null;
            });
        } catch (RuntimeException ex) {
            // 同上：整批失败就整批放弃，交给 TTL 兜底，不能让清理任务因此中断
            log.warn("evict cache batch failed, size={}: {}", shortCodes.size(), ex.toString());
        }
    }
}
