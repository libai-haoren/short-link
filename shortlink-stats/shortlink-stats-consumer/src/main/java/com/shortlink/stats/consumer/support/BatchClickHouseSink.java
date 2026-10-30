package com.shortlink.stats.consumer.support;

import com.shortlink.stats.consumer.service.ClickHouseWriter;
import com.shortlink.stats.consumer.service.ClickSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 批量写 ClickHouse 的缓冲层。
 *
 * <p>ClickHouse 最怕"一条一条 insert"：每次 insert 都会生成一个新 part，
 * 合并跟不上就会报 {@code Too many parts}。批量是硬要求，不是优化。</p>
 *
 * <p>缓冲满时<b>直接抛异常</b>而不是丢弃：消费端收到异常就不会提交位点，
 * 消息会被重投。这条链路允许变慢，不允许静默丢数据。</p>
 */
public class BatchClickHouseSink implements ClickSink, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BatchClickHouseSink.class);

    private final ClickHouseWriter writer;
    private final int batchSize;
    private final Duration flushInterval;
    private final BlockingQueue<ClickRow> buffer;

    private final Counter writtenCounter;
    private final Counter failedCounter;
    private final Counter rejectedCounter;

    private volatile Thread worker;
    private volatile boolean running;

    public BatchClickHouseSink(ClickHouseWriter writer,
                               int bufferSize,
                               int batchSize,
                               Duration flushInterval,
                               MeterRegistry meterRegistry) {
        this.writer = writer;
        this.batchSize = Math.max(1, batchSize);
        this.flushInterval = flushInterval == null ? Duration.ofSeconds(1) : flushInterval;
        this.buffer = new ArrayBlockingQueue<>(Math.max(this.batchSize, bufferSize));
        this.writtenCounter = Counter.builder("shortlink.click.written").register(meterRegistry);
        this.failedCounter = Counter.builder("shortlink.click.write.failed").register(meterRegistry);
        this.rejectedCounter = Counter.builder("shortlink.click.buffer.rejected").register(meterRegistry);
    }

    @Override
    public void accept(ClickRow row) {
        if (!buffer.offer(row)) {
            rejectedCounter.increment();
            throw new IllegalStateException("click buffer is full, size=" + buffer.size());
        }
        // 攒够一批就立刻刷，避免等定时器引入额外延迟
        if (buffer.size() >= batchSize) {
            flushNow();
        }
    }

    /**
     * 立即刷一批。
     *
     * @return 本次写入的行数
     */
    int flushNow() {
        List<ClickRow> batch = new ArrayList<>(batchSize);
        buffer.drainTo(batch, batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            writer.write(batch);
            writtenCounter.increment(batch.size());
            return batch.size();
        } catch (RuntimeException ex) {
            failedCounter.increment(batch.size());
            log.error("flush {} click rows failed", batch.size(), ex);
            throw ex;
        }
    }

    int bufferSize() {
        return buffer.size();
    }

    @Override
    public void start() {
        running = true;
        Thread thread = new Thread(this::loop, "click-ch-flusher");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        log.info("clickhouse flusher started, batchSize={}, interval={}", batchSize, flushInterval);
    }

    private void loop() {
        while (running) {
            try {
                // 定时把剩余的行刷出去：低频短链可能很久才凑够一批
                TimeUnit.MILLISECONDS.sleep(flushInterval.toMillis());
                if (!buffer.isEmpty()) {
                    flushNow();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException ex) {
                // 写失败不能让线程死掉，下一轮继续试
                log.warn("flush loop error: {}", ex.toString());
            }
        }
        // 退出前尽力冲一次
        try {
            while (!buffer.isEmpty()) {
                flushNow();
            }
        } catch (RuntimeException ex) {
            log.warn("final flush failed: {}", ex.toString());
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
}
