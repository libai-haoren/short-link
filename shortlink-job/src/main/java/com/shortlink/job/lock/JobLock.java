package com.shortlink.job.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis 的任务互斥锁。
 *
 * <p>多个实例同时跑定时任务会重复处理同一批数据（清理任务会重复删、对账任务会重复告警）。
 * 用 {@code SETNX + TTL} 保证"同一时刻只有一个实例在跑"；TTL 兜住"持有者进程崩了锁不释放"。</p>
 *
 * <p><b>释放必须校验持有者</b>：如果实例 A 的任务超时、锁被 TTL 自动释放、实例 B 拿到锁，
 * 此时 A 执行完直接 {@code DEL} 会把 B 的锁删掉。所以解锁用 Lua 做 compare-and-delete。</p>
 *
 * <p>这不是完美的分布式锁（Redisson 的看门狗、锁续期都没做），但对"每天跑一次的批处理"
 * 这个场景完全够用——任务本身是幂等的，偶尔并发一次也不会造成数据错误。</p>
 */
public class JobLock {

    private static final Logger log = LoggerFactory.getLogger(JobLock.class);

    private static final String PREFIX = "sl:job:lock:";

    private static final RedisScript<Long> UNLOCK = RedisScript.of("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final String instanceId = UUID.randomUUID().toString();

    public JobLock(StringRedisTemplate redisTemplate, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl == null || ttl.isZero() ? Duration.ofMinutes(2) : ttl;
    }

    /**
     * @return true 表示抢到锁，可以执行
     */
    public boolean tryLock(String jobName) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(PREFIX + jobName, instanceId, ttl);
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException ex) {
            // Redis 挂了：宁可让任务跑（幂等）也不要整个调度停摆
            log.warn("try lock failed, run anyway: job={}, reason={}", jobName, ex.toString());
            return true;
        }
    }

    public void unlock(String jobName) {
        try {
            redisTemplate.execute(UNLOCK, List.of(PREFIX + jobName), List.of(instanceId));
        } catch (RuntimeException ex) {
            log.warn("unlock failed, will expire by ttl: job={}, reason={}", jobName, ex.toString());
        }
    }
}
