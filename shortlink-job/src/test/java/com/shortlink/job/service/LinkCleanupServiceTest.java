package com.shortlink.job.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清理循环的边界：分批、上限、缓存失效。
 *
 * <p>这段逻辑最容易写出的 bug 是"循环不终止"和"删了但没失效缓存"，
 * 两者在单元测试里都能廉价地钉死。</p>
 */
class LinkCleanupServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 12, 0);

    static class FakeStore implements LinkStore {
        final List<List<ExpiredLink>> batches = new ArrayList<>();
        final List<Long> deleted = new ArrayList<>();
        LocalDateTime lastCutoff;
        int callCount;

        @Override
        public List<ExpiredLink> findExpired(LocalDateTime expiredBefore, int limit) {
            lastCutoff = expiredBefore;
            List<ExpiredLink> batch = callCount < batches.size() ? batches.get(callCount) : List.of();
            callCount++;
            return batch;
        }

        @Override
        public int markDeleted(List<Long> ids) {
            deleted.addAll(ids);
            return ids.size();
        }
    }

    static class FakeEvictor implements CacheEvictor {
        final List<String> evicted = new ArrayList<>();

        @Override
        public void evict(String shortCode) {
            evicted.add(shortCode);
        }
    }

    /** 只走批量路径的桩：用来钉住"一批一次调用"，防止哪天又被改成逐条发消息。 */
    static class FakeBatchEvictor implements CacheEvictor {
        final List<List<String>> calls = new ArrayList<>();

        @Override
        public void evict(String shortCode) {
            throw new AssertionError("清理任务不该走逐条失效");
        }

        @Override
        public void evictBatch(List<String> shortCodes) {
            calls.add(List.copyOf(shortCodes));
        }
    }

    private static List<LinkStore.ExpiredLink> batch(int size) {
        List<LinkStore.ExpiredLink> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new LinkStore.ExpiredLink((long) i, "code" + i));
        }
        return list;
    }

    private static LinkCleanupService service(LinkStore store, CacheEvictor evictor, int batchSize, int maxBatches) {
        return new LinkCleanupService(store, evictor, Duration.ofDays(30),
                batchSize, maxBatches, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("取完所有批次后自然结束，并逐条失效缓存")
    void cleansAllBatches() {
        FakeStore store = new FakeStore();
        store.batches.add(batch(2));
        store.batches.add(batch(1));
        FakeEvictor evictor = new FakeEvictor();

        JobResult result = service(store, evictor, 2, 10).cleanup(NOW);

        assertTrue(result.completed());
        assertEquals(3, result.scanned());
        assertEquals(2, result.batches());
        assertEquals(3, evictor.evicted.size(), "每一条软删的短链都要失效缓存");
    }

    @Test
    @DisplayName("达到批次上限就停，标记为未完成（下次继续）")
    void stopsAtMaxBatches() {
        FakeStore store = new FakeStore();
        // 永远有满批数据，模拟"数据量远超单次上限"
        for (int i = 0; i < 10; i++) {
            store.batches.add(batch(2));
        }
        FakeEvictor evictor = new FakeEvictor();

        JobResult result = service(store, evictor, 2, 3).cleanup(NOW);

        assertFalse(result.completed(), "还有数据时不能报告完成");
        assertEquals(3, result.batches());
        assertEquals(6, result.scanned());
    }

    @Test
    @DisplayName("没有过期数据时立刻结束，不产生任何删除")
    void nothingToDo() {
        FakeStore store = new FakeStore();
        FakeEvictor evictor = new FakeEvictor();

        JobResult result = service(store, evictor, 100, 10).cleanup(NOW);

        assertTrue(result.completed());
        assertEquals(0, result.scanned());
        assertEquals(0, result.batches());
        assertTrue(evictor.evicted.isEmpty());
    }

    @Test
    @DisplayName("查询用的是 now - 保留期，而不是 now")
    void usesRetentionWindow() {
        FakeStore store = new FakeStore();
        service(store, new FakeEvictor(), 10, 1).cleanup(NOW);

        assertEquals(NOW.minusDays(30), store.lastCutoff,
                "过期后再保留 30 天才清理，留出恢复窗口");
    }

    @Test
    @DisplayName("不满一批即视为扫完，不会多查一次")
    void partialBatchMeansDone() {
        FakeStore store = new FakeStore();
        store.batches.add(batch(3));
        FakeEvictor evictor = new FakeEvictor();

        JobResult result = service(store, evictor, 10, 10).cleanup(NOW);

        assertTrue(result.completed());
        assertEquals(1, store.callCount, "不满一批就不该再查第二次");
    }

    @Test
    @DisplayName("按批失效：两批数据只调用两次，不逐条发消息")
    void evictsOncePerBatch() {
        FakeStore store = new FakeStore();
        store.batches.add(batch(2));
        store.batches.add(batch(1));
        FakeBatchEvictor evictor = new FakeBatchEvictor();

        service(store, evictor, 2, 10).cleanup(NOW);

        assertEquals(2, evictor.calls.size(), "每批一次批量失效");
        assertEquals(List.of("code0", "code1"), evictor.calls.get(0));
        assertEquals(List.of("code0"), evictor.calls.get(1));
    }
}
