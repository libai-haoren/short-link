package com.shortlink.redirect.stats;

import com.shortlink.common.mq.EventPublisher;
import com.shortlink.common.mq.event.ClickEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 埋点采集器的核心承诺：<b>不影响跳转</b>。
 *
 * <p>所以这里重点测三件事：队列满了会丢、发布失败不冒泡、没配 Kafka 时自动禁用。</p>
 */
class ClickTrackerTest {

    private static ClickEvent event(String code) {
        return new ClickEvent(code + ":1:1", code, 1L, 1L, "ua", "", "trace");
    }

    private static ClickTracker tracker(EventPublisher publisher, boolean enabled, int queueSize, SimpleMeterRegistry registry) {
        return new ClickTracker(publisher, enabled, "salt", queueSize, 50, Duration.ofMillis(10), registry);
    }

    @Test
    @DisplayName("队列满时丢弃并计数，绝不阻塞")
    void dropsWhenQueueFull() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ClickTracker tracker = tracker((topic, key, ev) -> { }, true, 2, registry);

        assertTrue(tracker.offer(event("a")));
        assertTrue(tracker.offer(event("b")));
        assertFalse(tracker.offer(event("c")), "第三条应该被丢弃而不是阻塞");

        assertEquals(1.0, registry.counter("shortlink.click.tracked", "result", "dropped").count());
        assertEquals(2, tracker.queueSize());
    }

    @Test
    @DisplayName("发布失败只记 failed，不抛异常")
    void publishFailureIsSwallowed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EventPublisher broken = (topic, key, ev) -> {
            throw new IllegalStateException("kafka down");
        };
        ClickTracker tracker = tracker(broken, true, 10, registry);

        tracker.offer(event("a"));
        // 不抛异常即为通过
        int drained = tracker.drainOnce(false);

        assertEquals(1, drained);
        assertEquals(1.0, registry.counter("shortlink.click.tracked", "result", "failed").count());
        assertEquals(0.0, registry.counter("shortlink.click.tracked", "result", "published").count());
    }

    @Test
    @DisplayName("成功发布会计数，且按批次取走")
    void drainsBatch() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<ClickEvent> published = new ArrayList<>();
        ClickTracker tracker = tracker((topic, key, ev) -> published.add((ClickEvent) ev), true, 100, registry);

        for (int i = 0; i < 10; i++) {
            tracker.offer(event("c" + i));
        }
        int drained = tracker.drainOnce(false);

        assertEquals(10, drained);
        assertEquals(10, published.size());
        assertEquals(10.0, registry.counter("shortlink.click.tracked", "result", "published").count());
        assertEquals(0, tracker.queueSize());
    }

    @Test
    @DisplayName("没有 Kafka（publisher 为 null）时自动禁用，不占用队列")
    void disabledWithoutPublisher() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ClickTracker tracker = tracker(null, true, 10, registry);

        assertFalse(tracker.isEnabled());
        assertFalse(tracker.offer(event("a")));
        assertEquals(0, tracker.queueSize());
    }

    @Test
    @DisplayName("开关关闭时不产生任何副作用")
    void disabledByConfig() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<ClickEvent> published = new ArrayList<>();
        ClickTracker tracker = tracker((topic, key, ev) -> published.add((ClickEvent) ev), false, 10, registry);

        assertFalse(tracker.offer(event("a")));
        assertEquals(0, tracker.drainOnce(false));
        assertTrue(published.isEmpty());
    }
}
