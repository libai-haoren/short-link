package com.shortlink.job.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 过期短链清理。
 *
 * <p>三个必须做对的地方，少一个都会在生产上出事：</p>
 * <ol>
 *   <li><b>分批</b>：一条 {@code DELETE ... WHERE expire_time < ?} 扫全表会把 DB 打满。
 *       这里每批 {@code batchSize} 条，总量受 {@code maxBatches} 限制，跑不完下次继续；</li>
 *   <li><b>软删而不是物理删</b>：物理删掉后统计、审计都失去关联，误删也无法恢复；</li>
 *   <li><b>删完必须失效缓存</b>，否则短链在 Redis 里还活着，用户仍然能跳转——
 *       清理任务等于白跑。而且要发 Pub/Sub，否则 redirect 的 L1 还会继续命中。</li>
 * </ol>
 *
 * <p>为什么用"过期时间 + 保留期"而不是"30 天未访问"：后者需要 last_access_time 字段，
 * 而每次跳转都写一次时间戳会把读链路变成写链路。用过期时间做判据足够，
 * 且不需要额外的写入成本。</p>
 */
public class LinkCleanupService {

    private static final Logger log = LoggerFactory.getLogger(LinkCleanupService.class);

    public static final String JOB_NAME = "expired-link-cleanup";

    private final LinkStore linkStore;
    private final CacheEvictor cacheEvictor;
    private final Duration retention;
    private final int batchSize;
    private final int maxBatches;
    private final Counter cleanedCounter;

    public LinkCleanupService(LinkStore linkStore,
                              CacheEvictor cacheEvictor,
                              Duration retention,
                              int batchSize,
                              int maxBatches,
                              MeterRegistry meterRegistry) {
        this.linkStore = linkStore;
        this.cacheEvictor = cacheEvictor;
        this.retention = retention;
        this.batchSize = Math.max(1, batchSize);
        this.maxBatches = Math.max(1, maxBatches);
        this.cleanedCounter = Counter.builder("shortlink.job.cleaned")
                .description("被清理（软删）的过期短链数")
                .register(meterRegistry);
    }

    /**
     * 执行清理。
     *
     * @param now 显式传入，便于测试边界
     */
    public JobResult cleanup(LocalDateTime now) {
        long start = System.currentTimeMillis();
        LocalDateTime expiredBefore = now.minus(retention);

        long affectedTotal = 0;
        int batches = 0;
        boolean completed = false;

        while (batches < maxBatches) {
            List<LinkStore.ExpiredLink> batch = linkStore.findExpired(expiredBefore, batchSize);
            if (batch.isEmpty()) {
                completed = true;
                break;
            }
            List<Long> ids = batch.stream().map(LinkStore.ExpiredLink::id).toList();
            int affected = linkStore.markDeleted(ids);
            affectedTotal += affected;
            batches++;

            // 删完立刻失效缓存：Redis 主缓存 + Pub/Sub 通知 redirect 清 L1
            for (LinkStore.ExpiredLink link : batch) {
                cacheEvictor.evict(link.shortCode());
            }
            cleanedCounter.increment(affected);

            if (batch.size() < batchSize) {
                // 这一批没取满，说明后面没有了
                completed = true;
                break;
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("expired-link-cleanup done: affected={}, batches={}, completed={}, elapsed={}ms",
                affectedTotal, batches, completed, elapsed);
        return completed
                ? JobResult.completed(JOB_NAME, affectedTotal, batches, elapsed)
                : JobResult.partial(JOB_NAME, affectedTotal, batches, elapsed);
    }
}
