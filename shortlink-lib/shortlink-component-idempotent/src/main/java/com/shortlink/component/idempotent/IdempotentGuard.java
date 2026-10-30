package com.shortlink.component.idempotent;

import com.shortlink.common.cache.CacheKeys;
import com.shortlink.common.core.exception.BizException;
import com.shortlink.common.core.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 幂等执行器。
 *
 * <p>语义：同一幂等键在 TTL 内只真正执行一次，后续请求<b>返回首次的结果</b>，
 * 而不是报错。这一点很关键——"重复提交返回 409"对调用方毫无帮助，
 * 它真正想要的是"告诉我第一次的结果是什么"。</p>
 *
 * <p>三个必须做对的点：</p>
 * <ol>
 *   <li><b>先占位再执行</b>：如果先执行业务再写标记，两个并发请求都会穿过去；</li>
 *   <li><b>执行失败要释放占位</b>：否则用户永远无法重试；</li>
 *   <li><b>并发重复要等待而不是立刻失败</b>：第一个请求还在跑时，第二个请求会轮询等待结果，
 *       最多等 {@code waitTimeout}（默认 3s）。</li>
 * </ol>
 *
 * <p>轮询用 {@code Thread.sleep} 而不是异步：调用方是 MVC 的 admin（写 QPS 个位数），
 * 阻塞一个 Tomcat 线程 3 秒完全可以接受；换成异步反而要把接口改成响应式，不值。</p>
 */
public class IdempotentGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotentGuard.class);

    /** 占位标记。用不可见前缀避免和正常 JSON 结果混淆。 */
    private static final String PROCESSING = "\u0000PROCESSING";

    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final StringRedisTemplate redisTemplate;
    private final Duration waitTimeout;

    public IdempotentGuard(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_WAIT_TIMEOUT);
    }

    public IdempotentGuard(StringRedisTemplate redisTemplate, Duration waitTimeout) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.waitTimeout = waitTimeout;
    }

    /**
     * 执行幂等逻辑。
     *
     * @param key    幂等键（客户端生成，如 UUID）
     * @param ttl    结果保留时长
     * @param action 首次执行的业务逻辑
     * @param codec  结果编解码
     * @return 首次执行的结果；重复请求返回缓存结果
     * @throws BizException 首次请求仍在处理中且等待超时，或首次请求失败
     */
    public <T> T execute(String key, Duration ttl, Supplier<T> action, ResultCodec<T> codec) {
        Objects.requireNonNull(key, "idempotency key must not be null");
        String redisKey = CacheKeys.idempotent(key);

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, PROCESSING, ttl);
        if (Boolean.TRUE.equals(acquired)) {
            return runAndCache(redisKey, ttl, action, codec);
        }
        return awaitResult(redisKey, codec);
    }

    private <T> T runAndCache(String redisKey, Duration ttl, Supplier<T> action, ResultCodec<T> codec) {
        T result;
        try {
            result = action.get();
        } catch (RuntimeException ex) {
            // 业务失败必须释放占位，否则用户无法重试
            redisTemplate.delete(redisKey);
            throw ex;
        }
        try {
            redisTemplate.opsForValue().set(redisKey, codec.encode(result), ttl);
        } catch (RuntimeException ex) {
            // 结果没写进去不影响本次返回，只是重复请求拿不到缓存，会退化成"再执行一次"
            log.warn("cache idempotent result failed, key={}: {}", redisKey, ex.toString());
        }
        return result;
    }

    private <T> T awaitResult(String redisKey, ResultCodec<T> codec) {
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            String payload = redisTemplate.opsForValue().get(redisKey);
            if (payload == null) {
                // 占位被删掉了 → 上一个请求失败了，让调用方重试
                throw new BizException(ErrorCode.IDEMPOTENT_IN_PROGRESS, "上次相同请求未成功，请重试");
            }
            if (!PROCESSING.equals(payload)) {
                return codec.decode(payload);
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new BizException(ErrorCode.IDEMPOTENT_IN_PROGRESS);
    }
}
