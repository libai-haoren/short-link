package com.shortlink.stats.consumer.service;

import com.shortlink.common.mq.event.ClickEvent;
import com.shortlink.stats.consumer.support.ClickRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 处理流水线的正确性：去重、失败不提交位点、实时计数失败不影响落库。
 */
class ClickProcessorTest {

    static class FakeDedup implements DedupGuard {
        final Set<String> seen = new HashSet<>();
        final List<String> forgotten = new ArrayList<>();

        @Override
        public boolean firstSeen(String eventId) {
            return seen.add(eventId);
        }

        @Override
        public void forget(String eventId) {
            forgotten.add(eventId);
            seen.remove(eventId);
        }
    }

    static class FakeSink implements ClickSink {
        final List<ClickRow> rows = new ArrayList<>();
        boolean fail;

        @Override
        public void accept(ClickRow row) {
            if (fail) {
                throw new IllegalStateException("clickhouse down");
            }
            rows.add(row);
        }
    }

    static class FakeCounter implements RealtimeCounter {
        int calls;
        boolean fail;

        @Override
        public void increment(String shortCode, long eventTimeMillis, long ipHash) {
            if (fail) {
                throw new IllegalStateException("redis down");
            }
            calls++;
        }
    }

    private static ClickEvent event(String id) {
        return new ClickEvent(id, "abc1234", 1_700_000_000_000L, 42L,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0", "https://example.com", "trace-1");
    }

    private static ClickProcessor processor(FakeDedup dedup, FakeSink sink, FakeCounter counter) {
        return new ClickProcessor(dedup, sink, counter, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("正常处理：落库 + 实时计数各一次")
    void happyPath() {
        FakeDedup dedup = new FakeDedup();
        FakeSink sink = new FakeSink();
        FakeCounter counter = new FakeCounter();

        assertTrue(processor(dedup, sink, counter).handle(event("e1")));

        assertEquals(1, sink.rows.size());
        assertEquals(1, counter.calls);
        ClickRow row = sink.rows.get(0);
        assertEquals("abc1234", row.shortCode());
        assertEquals(42L, row.ipHash());
        assertEquals("Chrome", row.browser());
        assertEquals("Windows", row.os());
        assertEquals(0, row.isBot());
    }

    @Test
    @DisplayName("重复事件被拦截：不落库，但仍然返回 true（可以提交位点）")
    void duplicateIsSkipped() {
        FakeDedup dedup = new FakeDedup();
        FakeSink sink = new FakeSink();
        FakeCounter counter = new FakeCounter();
        ClickProcessor processor = processor(dedup, sink, counter);

        assertTrue(processor.handle(event("e1")));
        assertTrue(processor.handle(event("e1")), "重复消息不能导致位点卡住");

        assertEquals(1, sink.rows.size(), "重复事件不应该写第二条");
        assertEquals(1, counter.calls);
    }

    @Test
    @DisplayName("落库失败：返回 false 且撤销去重标记，否则重投时会被误判为重复而丢数据")
    void sinkFailureReleasesDedup() {
        FakeDedup dedup = new FakeDedup();
        FakeSink sink = new FakeSink();
        sink.fail = true;
        FakeCounter counter = new FakeCounter();

        boolean handled = processor(dedup, sink, counter).handle(event("e1"));

        assertFalse(handled, "落库失败必须返回 false，让消费端不提交位点");
        assertEquals(List.of("e1"), dedup.forgotten);
        assertEquals(0, counter.calls, "落库失败不应再计实时数");
    }

    @Test
    @DisplayName("实时计数失败不影响落库，也不触发重投（否则明细会重复）")
    void realtimeFailureDoesNotBlockSink() {
        FakeDedup dedup = new FakeDedup();
        FakeSink sink = new FakeSink();
        FakeCounter counter = new FakeCounter();
        counter.fail = true;

        assertTrue(processor(dedup, sink, counter).handle(event("e1")));
        assertEquals(1, sink.rows.size());
    }

    @Test
    @DisplayName("爬虫事件标记 is_bot=1，后续统计靠它剔除")
    void botFlagged() {
        FakeDedup dedup = new FakeDedup();
        FakeSink sink = new FakeSink();
        ClickProcessor processor = processor(dedup, sink, new FakeCounter());

        processor.handle(new ClickEvent("e2", "abc1234", 1L, 1L,
                "Mozilla/5.0 (compatible; Googlebot/2.1)", "", ""));

        assertEquals(1, sink.rows.get(0).isBot());
    }

    @Test
    @DisplayName("空事件/空短码直接跳过，不落库")
    void invalidEventSkipped() {
        FakeDedup dedup = new FakeDedup();
        FakeSink sink = new FakeSink();
        ClickProcessor processor = processor(dedup, sink, new FakeCounter());

        assertTrue(processor.handle(null));
        assertTrue(processor.handle(new ClickEvent("e3", "  ", 1L, 1L, "", "", "")));
        assertTrue(sink.rows.isEmpty());
    }
}
