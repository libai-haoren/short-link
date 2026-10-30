package com.shortlink.component.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地限流器的容量与隔离性。
 *
 * <p>端到端只能证明"打得穿会 429"，但它依赖时序，测不出边界。
 * 这里用确定性的方式验证：容量内全放行、超容量拒绝、不同 key 互不影响。</p>
 */
class LocalRateLimiterTest {

    private static LocalRateLimiter limiter(long capacity) {
        // refill 与 capacity 相同 → 每秒补满一桶；测试在毫秒内跑完，补充量可忽略
        return new LocalRateLimiter(1000L, Duration.ofMinutes(1), capacity, capacity, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("容量内全部放行")
    void allowsWithinCapacity() {
        LocalRateLimiter limiter = limiter(100);
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (limiter.tryAcquire("code-a", 1)) {
                allowed++;
            }
        }
        assertEquals(100, allowed);
    }

    @Test
    @DisplayName("超过容量后拒绝（允许补充带来的极少数额外放行）")
    void rejectsBeyondCapacity() {
        LocalRateLimiter limiter = limiter(100);
        int allowed = 0;
        for (int i = 0; i < 300; i++) {
            if (limiter.tryAcquire("code-b", 1)) {
                allowed++;
            }
        }
        assertTrue(allowed >= 100, "至少应放行容量个，实际 " + allowed);
        assertTrue(allowed <= 105, "不应超过容量太多（补充只该带来个位数），实际 " + allowed);
    }

    @Test
    @DisplayName("不同 key 之间互不影响")
    void keysAreIsolated() {
        LocalRateLimiter limiter = limiter(10);

        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("hot-code", 1);
        }
        // hot-code 已经耗尽
        assertTrue(!limiter.tryAcquire("hot-code", 1), "热点码应已被限流");

        // 另一个码不受影响
        assertTrue(limiter.tryAcquire("cold-code", 1), "冷门码不应被连带限流");
    }

    @Test
    @DisplayName("一次性取多个令牌：不足时整体失败，不会部分扣减")
    void acquireMultipleIsAtomic() {
        LocalRateLimiter limiter = limiter(10);
        for (int i = 0; i < 8; i++) {
            limiter.tryAcquire("code-c", 1);
        }
        // 剩 2 个，要 5 个应失败
        assertTrue(!limiter.tryAcquire("code-c", 5), "令牌不足时应整体失败");
        // 失败后仍有 2 个可用
        assertTrue(limiter.tryAcquire("code-c", 2), "失败不应扣减令牌");
    }
}
