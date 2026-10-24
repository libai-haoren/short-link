package com.shortlink.component.idgen;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 号段发号器（双 Buffer）。
 *
 * <p>核心思路：一次向 DB 要 {@code step} 个号，在内存里发；剩余 20% 时<b>异步</b>预取下一段，
 * 这样取号本身永远是内存操作，DB 抖动也不会让发号变慢。</p>
 *
 * <p>几个容易踩的坑，已在此处处理：</p>
 * <ul>
 *   <li><b>预取必须异步</b>：同步预取等于把 DB 的 RT 加到发号路径上，白折腾。</li>
 *   <li><b>预取失败不能影响当前号段</b>：失败只记日志，下次触发了再试。</li>
 *   <li><b>重启会丢弃当前号段剩余号</b>：最多浪费 step 个号，换来实现简单，可接受。</li>
 *   <li><b>锁粒度</b>：只在“换段 + 取号”时加锁，预取在锁外。admin 写 QPS 很低，
 *       synchronized 足够；若未来发号 QPS 上万，改成 AtomicLong 游标 + CAS 换段的无锁实现。</li>
 * </ul>
 */
public class SegmentIdGenerator {

    /** 剩余比例低于该值时触发预取。取 0.2 是在“预取太晚”和“预取太频繁”之间的折中。 */
    private static final double PREFETCH_RATIO = 0.2D;

    private final String bizTag;
    private final int step;
    private final SegmentAllocator allocator;
    private final Executor prefetchExecutor;

    private final Object lock = new Object();
    private final AtomicBoolean prefetching = new AtomicBoolean(false);

    private Cursor current;
    private SegmentAllocator.Segment prefetched;

    public SegmentIdGenerator(String bizTag, int step, SegmentAllocator allocator) {
        this(bizTag, step, allocator, defaultExecutor());
    }

    public SegmentIdGenerator(String bizTag, int step, SegmentAllocator allocator, Executor prefetchExecutor) {
        if (step <= 0) {
            throw new IllegalArgumentException("step must be > 0");
        }
        this.bizTag = bizTag;
        this.step = step;
        this.allocator = allocator;
        this.prefetchExecutor = prefetchExecutor;
    }

    /** 取下一个号。线程安全。 */
    public long nextId() {
        SegmentAllocator.Segment needPrefetch = null;
        long id;
        synchronized (lock) {
            if (current == null || !current.hasRemaining()) {
                current = new Cursor(takeSegmentLocked());
            }
            id = current.take();
            if (current.remaining() <= step * PREFETCH_RATIO && prefetched == null) {
                needPrefetch = currentSegmentBounds();
            }
        }
        if (needPrefetch != null) {
            triggerAsyncPrefetch();
        }
        return id;
    }

    /** 仅供监控/调试：当前号段剩余可发数量。 */
    public long remaining() {
        synchronized (lock) {
            return current == null ? 0 : current.remaining();
        }
    }

    /**
     * 必须在 lock 内调用。
     * 优先用预取好的段；否则同步向 DB 要一段（只在首次或预取失败时发生）。
     */
    private SegmentAllocator.Segment takeSegmentLocked() {
        if (prefetched != null) {
            SegmentAllocator.Segment segment = prefetched;
            prefetched = null;
            return segment;
        }
        return allocator.allocate(bizTag, step);
    }

    private void triggerAsyncPrefetch() {
        if (!prefetching.compareAndSet(false, true)) {
            return;
        }
        try {
            prefetchExecutor.execute(() -> {
                try {
                    SegmentAllocator.Segment segment = allocator.allocate(bizTag, step);
                    synchronized (lock) {
                        // 双检：并发下可能已有别的线程填过
                        if (prefetched == null) {
                            prefetched = segment;
                        }
                    }
                } catch (Exception ex) {
                    // 预取失败不影响当前号段；下一次剩余量检查会再次触发
                    PREFETCH_LOGGER.log(System.Logger.Level.WARNING,
                            "prefetch segment failed, bizTag=" + bizTag, ex);
                } finally {
                    prefetching.set(false);
                }
            });
        } catch (RuntimeException ex) {
            prefetching.set(false);
            throw ex;
        }
    }

    private SegmentAllocator.Segment currentSegmentBounds() {
        return new SegmentAllocator.Segment(current.start, current.end);
    }

    private static Executor defaultExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "idgen-prefetch");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    private static final System.Logger PREFETCH_LOGGER =
            System.getLogger(SegmentIdGenerator.class.getName());

    /** 号段游标。非线程安全，由 generator 的 lock 保护。 */
    private static final class Cursor {
        private final long start;
        private final long end;
        private final AtomicLong next;

        Cursor(SegmentAllocator.Segment segment) {
            this.start = segment.startInclusive();
            this.end = segment.endExclusive();
            this.next = new AtomicLong(segment.startInclusive());
        }

        boolean hasRemaining() {
            return next.get() < end;
        }

        long remaining() {
            return Math.max(0L, end - next.get());
        }

        long take() {
            long value = next.getAndIncrement();
            if (value >= end) {
                throw new IllegalStateException("segment exhausted: [" + start + ", " + end + ")");
            }
            return value;
        }
    }
}
