package com.shortlink.redirect.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisCacheReadinessTest {

    @Test
    @DisplayName("闸门关闭时退化为永远就绪")
    void disabledGateIsAlwaysReady() {
        // 未调用 start()、也没有 Redis：关闭态的语义就是"不参与判定"
        CacheReadiness gate = new RedisCacheReadiness(null, false, Duration.ofSeconds(2));

        assertTrue(gate.isReady());
    }

    @Test
    @DisplayName("闸门开启且尚未探测时按未就绪处理（fail-closed）")
    void enabledGateStartsClosed() {
        CacheReadiness gate = new RedisCacheReadiness(null, true, Duration.ofSeconds(2));

        // 没探测过就不能放行未命中：宁可 503，也不要谎报 404
        assertFalse(gate.isReady());
    }
}
