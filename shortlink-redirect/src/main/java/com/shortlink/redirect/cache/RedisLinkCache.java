package com.shortlink.redirect.cache;

import com.shortlink.common.cache.CacheKeys;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Redis 层（L2）。
 *
 * <p>用 {@code HMGET} 而不是 {@code HGETALL}：字段固定五个，少传字段名、少一次解析，
 * 而且返回顺序稳定，正好对应 {@link LinkSnapshot#parse} 的下标。</p>
 *
 * <p>注意 {@code shortCode} 字段虽然在 Hash 里，但这里刻意不取——redirect 已经知道短码了，
 * 多取一个字段只是浪费带宽。</p>
 */
public class RedisLinkCache implements LinkCache {

    /**
     * 必须与 {@code LinkSnapshot.parse} 的参数顺序、以及 admin 侧
     * {@code LinkCacheWriter} 写入的字段名保持一致。
     */
    private static final List<String> FIELDS =
            List.of("longUrl", "status", "expireTime", "maxClick", "domainId", "version");

    private final ReactiveHashOperations<String, String, String> hashOperations;

    public RedisLinkCache(ReactiveStringRedisTemplate redisTemplate) {
        this.hashOperations = redisTemplate.opsForHash();
    }

    @Override
    public Mono<LinkSnapshot> get(String shortCode) {
        return hashOperations.multiGet(CacheKeys.link(shortCode), FIELDS)
                .flatMap(values -> {
                    // 整个 Hash 不存在时，multiGet 返回的 list 每个元素都是 null
                    if (values == null || values.isEmpty() || values.get(0) == null) {
                        return Mono.empty();
                    }
                    return Mono.fromCallable(() -> LinkSnapshot.parse(values));
                })
                .onErrorMap(this::translate);
    }

    /**
     * 空操作：Redis 就是数据源，admin 已经写过新值了，这里没有任何"本地副本"需要清。
     *
     * <p>保留这两个方法是接口契约要求——上层的失效广播不关心下面有没有 L1。</p>
     */
    @Override
    public void invalidate(String shortCode) {
        // no-op
    }

    @Override
    public void invalidateAll() {
        // no-op
    }

    /**
     * 把底层技术异常统一成领域异常，避免 Lettuce/Netty 的异常类型泄漏到上层。
     * 解析异常原样透传，它已经有明确语义。
     */
    private Throwable translate(Throwable error) {
        if (error instanceof CorruptCacheDataException || error instanceof CacheUnavailableException) {
            return error;
        }
        return new CacheUnavailableException("read short link cache failed", error);
    }
}
