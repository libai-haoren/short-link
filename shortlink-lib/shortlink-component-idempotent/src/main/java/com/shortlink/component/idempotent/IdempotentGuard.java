package com.shortlink.component.idempotent;

import com.shortlink.common.cache.CacheKeys;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 幂等执行器。
 *
 * <p>语义：同一幂等键在 TTL 内只会真正执行一次，后续请求返回首次结果。</p>
 *
 * <p>实现取舍：</p>
 * <ul>
 *   <li><b>SETNX 而不是 GET+SET</b>：两步操作在并发下有竞态，SETNX 是原子的。</li>
 *   <li><b>先占位再执行</b>：如果先执行业务再写标记，两个并发请求都会穿过。</li>
 *   <li><b>执行失败要释放占位</b>：否则用户重试会一直拿到“处理中”，业务永远做不成。</li>
 *   <li><b>结果序列化</b>：真实落地要存 JSON；骨架先留空实现，V4 接 Jackson。</li>
 * </ul>
 *
 * <p>TODO(V4)：并发场景下第二个请求会立即失败（而不是等待首个请求完成），
 * 需要增加“短暂轮询结果”逻辑，否则前端体验是报错而不是幂等返回。</p>
 */
public class IdempotentGuard {

    private static final String PROCESSING = "PROCESSING";

    private final StringRedisTemplate redisTemplate;

    public IdempotentGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
    }

    /**
     * 执行幂等逻辑。
     *
     * @param key    幂等键（客户端生成，如 UUID）
     * @param ttl    结果保留时长
     * @param action 真正的业务逻辑
     * @return 首次执行的结果
     * @throws IllegalStateException 幂等键已被占用（重复提交）
     */
    public <T> T execute(String key, Duration ttl, Supplier<T> action) {
        Objects.requireNonNull(key, "idempotency key must not be null");
        String redisKey = CacheKeys.idempotent(key);

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, PROCESSING, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            // TODO(V4): 读取已缓存结果返回；当前抛异常，由上层翻译成“重复提交”
            throw new IllegalStateException("duplicated request, idempotency key=" + key);
        }

        try {
            T result = action.get();
            // TODO(V4): 把 result 序列化后写回 redisKey，供重复请求返回
            return result;
        } catch (RuntimeException ex) {
            // 业务失败必须释放占位，否则用户无法重试
            redisTemplate.delete(redisKey);
            throw ex;
        }
    }
}
