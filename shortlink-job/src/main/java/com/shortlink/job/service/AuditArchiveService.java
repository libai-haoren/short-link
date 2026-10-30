package com.shortlink.job.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志归档：删掉 N 天前的 {@code t_operate_log}。
 *
 * <p>审计表只增不减，不清理的话一年后单表上亿行，连"按业务 ID 查变更历史"都会变慢。
 * 设计上说的是"按月归档到冷存储"，这里做的是更简单的"定期删除"——
 * 真正的归档需要对象存储，当前环境没有，所以先把"不会无限膨胀"这件事做掉。</p>
 *
 * <p>同样分批：一次删几百万行会长时间持有锁。</p>
 */
public class AuditArchiveService {

    private static final Logger log = LoggerFactory.getLogger(AuditArchiveService.class);

    public static final String JOB_NAME = "audit-log-archive";

    private final JdbcTemplate jdbcTemplate;
    private final int retentionDays;
    private final int batchSize;
    private final int maxBatches;
    private final Counter deletedCounter;

    public AuditArchiveService(JdbcTemplate jdbcTemplate,
                               int retentionDays,
                               int batchSize,
                               int maxBatches,
                               MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = Math.max(1, retentionDays);
        this.batchSize = Math.max(1, batchSize);
        this.maxBatches = Math.max(1, maxBatches);
        this.deletedCounter = Counter.builder("shortlink.job.audit.deleted")
                .description("被归档删除的审计日志条数")
                .register(meterRegistry);
    }

    public JobResult archive(LocalDateTime now) {
        long start = System.currentTimeMillis();
        LocalDateTime cutoff = now.minusDays(retentionDays);

        long deletedTotal = 0;
        int batches = 0;
        boolean completed = false;

        while (batches < maxBatches) {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM t_operate_log WHERE create_time < ? ORDER BY id LIMIT ?",
                    Long.class, cutoff, batchSize);
            if (ids.isEmpty()) {
                completed = true;
                break;
            }
            String sql = "DELETE FROM t_operate_log WHERE id IN ("
                    + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")";
            int deleted = jdbcTemplate.update(sql, ids.toArray());
            deletedTotal += deleted;
            batches++;
            deletedCounter.increment(deleted);

            if (ids.size() < batchSize) {
                completed = true;
                break;
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("audit-log-archive done: deleted={}, batches={}, completed={}, elapsed={}ms",
                deletedTotal, batches, completed, elapsed);
        return completed
                ? JobResult.completed(JOB_NAME, deletedTotal, batches, elapsed)
                : JobResult.partial(JOB_NAME, deletedTotal, batches, elapsed);
    }
}
