package com.shortlink.redirect.stats;

import com.shortlink.common.mq.EventPublisher;
import com.shortlink.common.mq.Topics;
import com.shortlink.common.mq.event.ClickEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 点击埋点采集器。
 *
 * <p><b>核心约束：埋点永远不能影响跳转。</b>所以这里做了三件事：</p>
 * <ol>
 *   <li><b>有界队列</b>：队列满就丢弃并计数，绝不阻塞请求线程；</li>
 *   <li><b>独立线程发送</b>：Kafka 发不出去时排队的是这个线程，不是 Netty 的 IO 线程；</li>
 *   <li><b>批量发送</b>：一次 drain 出多个事件，减少线程唤醒与网络往返。</li>
 * </ol>
 *
 * <p>为什么不用 {@code KafkaTemplate.send} 直接发：那个调用在元数据不可用时会阻塞
 * （{@code max.block.ms} 默认 60 秒）。一次 Kafka 抖动就能让整个跳转服务假死——
 * 这是"异步"两个字背后真正的坑。</p>
 *
 * <p>禁用时（默认）完全零开销：{@link #track} 直接返回，连哈希都不算。</p>
 */
public class ClickTracker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ClickTracker.class);

    private final EventPublisher publisher;
    private final boolean enabled;
    private final String ipSalt;
    private final BlockingQueue<ClickEvent> queue;
    private final int batchSize;
    private final Duration pollTimeout;

    private final Counter droppedCounter;
    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final AtomicLong lastErrorLog = new AtomicLong();

    private volatile Thread worker;
    private volatile boolean running;

    public ClickTracker(EventPublisher publisher,
                        boolean enabled,
                        String ipSalt,
                        int queueSize,
                        int batchSize,
                        Duration pollTimeout,
                        MeterRegistry meterRegistry) {
        this.publisher = publisher;
        // 没有 KafkaTemplate（没配 MQ）时，即便开关打开也只能空转，直接判为不可用
        this.enabled = enabled && publisher != null;
        this.ipSalt = ipSalt == null ? "" : ipSalt;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueSize));
        this.batchSize = Math.max(1, batchSize);
        this.pollTimeout = pollTimeout == null ? Duration.ofMillis(200) : pollTimeout;
        this.droppedCounter = Counter.builder("shortlink.click.tracked")
                .tag("result", "dropped").register(meterRegistry);
        this.publishedCounter = Counter.builder("shortlink.click.tracked")
                .tag("result", "published").register(meterRegistry);
        this.failedCounter = Counter.builder("shortlink.click.tracked")
                .tag("result", "failed").register(meterRegistry);
    }

    /**
     * 记录一次点击。<b>永远不会抛异常，也永远不会阻塞。</b>
     *
     * @return true 表示成功入队
     */
    public boolean track(ServerWebExchange exchange, String shortCode) {
        if (!enabled) {
            return false;
        }
        try {
            return offer(ClickEvents.build(exchange, shortCode, ipSalt, System.currentTimeMillis()));
        } catch (RuntimeException ex) {
            // 构造事件都不该失败，但真失败了也绝不能冒泡到跳转
            logOnce("build click event failed: " + ex);
            return false;
        }
    }

    /** 直接入队（供单测与内部使用）。 */
    boolean offer(ClickEvent event) {
        if (!enabled) {
            return false;
        }
        if (!queue.offer(event)) {
            droppedCounter.increment();
            logOnce("click queue is full, dropping events");
            return false;
        }
        return true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int queueSize() {
        return queue.size();
    }

    public int capacity() {
        return queue.size() + queue.remainingCapacity();
    }

    /**
     * 取一批事件发出去。
     *
     * <p>抽成独立方法是为了单测：不启线程也能验证"发几个、失败怎么算"。</p>
     *
     * @return 实际取出的事件数
     */
    int drainOnce(boolean waitForFirst) {
        List<ClickEvent> batch = new ArrayList<>(batchSize);
        try {
            if (waitForFirst) {
                ClickEvent first = queue.poll(pollTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    return 0;
                }
                batch.add(first);
            }
            queue.drainTo(batch, batchSize - batch.size());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return 0;
        }
        for (ClickEvent event : batch) {
            publishSafely(event);
        }
        return batch.size();
    }

    private void publishSafely(ClickEvent event) {
        try {
            publisher.publish(Topics.CLICK, event.shortCode(), event);
            publishedCounter.increment();
        } catch (RuntimeException ex) {
            // 单条失败不影响后续事件，跳转更不会受影响
            failedCounter.increment();
            logOnce("publish click event failed: " + ex);
        }
    }

    /** 同类错误只打一条 warn，避免 Kafka 挂掉时把磁盘写满。 */
    private void logOnce(String message) {
        long now = System.currentTimeMillis();
        long last = lastErrorLog.get();
        if (now - last > 10_000L && lastErrorLog.compareAndSet(last, now)) {
            log.warn(message);
        }
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("click tracker disabled (stats.enabled=false or no kafka)");
            return;
        }
        running = true;
        Thread thread = new Thread(this::loop, "click-tracker");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        log.info("click tracker started, capacity={}, batchSize={}", capacity(), batchSize);
    }

    private void loop() {
        while (running) {
            try {
                drainOnce(true);
            } catch (RuntimeException ex) {
                logOnce("click tracker loop error: " + ex);
            }
        }
        // 退出前把剩余事件尽量冲出去（不保证全部成功）
        while (!queue.isEmpty()) {
            drainOnce(false);
        }
    }

    @Override
    public void stop() {
        running = false;
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
            worker = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 供测试断言使用。 */
    Counter counter(String result) {
        return switch (result) {
            case "dropped" -> droppedCounter;
            case "published" -> publishedCounter;
            default -> failedCounter;
        };
    }
}
