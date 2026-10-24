package com.shortlink.component.ratelimit;

import java.time.Duration;

/**
 * 令牌桶。
 *
 * <p>相比固定窗口计数器，令牌桶允许“突发流量”：桶容量就是允许的最大突发量，
 * 而长期平均速率由补充速率决定。这正是网关限流想要的语义——
 * 正常用户偶尔手快连点几下不应该被拒，但持续刷就要拦。</p>
 *
 * <p>实现要点：不启后台线程定时补桶，而是<b>惰性计算</b>——
 * 每次取令牌时按经过的纳秒数算出应补多少。没有后台线程，也就没有“n 个桶 n 个线程”的问题。</p>
 *
 * <p>线程安全：所有 public 方法 synchronized。热点 key 下会有竞争，
 * 但单次计算是纯算术，临界区极短（纳秒级）。</p>
 */
public final class TokenBucket {

    private final long capacity;
    private final double refillPerNano;

    private double tokens;
    private long lastRefillNanos;

    /**
     * @param capacity     桶容量（最大突发量）
     * @param refillTokens 一个 {@code period} 内补充的令牌数
     * @param period       补充周期
     */
    public TokenBucket(long capacity, long refillTokens, Duration period) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillTokens <= 0 || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("refillTokens and period must be positive");
        }
        this.capacity = capacity;
        this.refillPerNano = (double) refillTokens / period.toNanos();
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * 尝试取令牌。
     *
     * @param permits 需要的令牌数，必须 &lt;= capacity
     * @return 取到返回 true，不足返回 false（不阻塞，由调用方决定降级策略）
     */
    public synchronized boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        if (permits > capacity) {
            throw new IllegalArgumentException("permits(" + permits + ") > capacity(" + capacity + ")");
        }
        refill();
        if (tokens >= permits) {
            tokens -= permits;
            return true;
        }
        return false;
    }

    /** 当前可用令牌数（向下取整），用于监控。 */
    public synchronized long availableTokens() {
        refill();
        return (long) tokens;
    }

    public long capacity() {
        return capacity;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        double added = elapsed * refillPerNano;
        if (added > 0) {
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }
}
