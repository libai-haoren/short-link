package com.shortlink.component.idgen;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 基于 MySQL 乐观锁的号段分配器。
 *
 * <p>SQL 语义：{@code UPDATE t_id_segment SET max_id = max_id + step, version = version + 1
 * WHERE biz_tag = ? AND version = ?}。</p>
 *
 * <p>为什么带 version 而不是直接 {@code max_id + step}：并发下两个实例可能读到同一个 max_id，
 * 各自算出重叠区间，就会发出重复号。version 让其中一个失败并重试。</p>
 *
 * <p>不用 {@code SELECT ... FOR UPDATE}：行锁持有时间更长，且高并发下死锁概率更高，
 * 乐观锁失败重试的成本更低。</p>
 */
public class DbSegmentAllocator implements SegmentAllocator {

    private static final int MAX_RETRY = 3;

    private static final String SELECT_SQL =
            "SELECT max_id, version FROM t_id_segment WHERE biz_tag = ?";

    private static final String UPDATE_SQL =
            "UPDATE t_id_segment SET max_id = max_id + ?, version = version + 1 "
                    + "WHERE biz_tag = ? AND version = ?";

    private final JdbcTemplate jdbcTemplate;

    public DbSegmentAllocator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Segment allocate(String bizTag, int step) {
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            StepRow row = jdbcTemplate.queryForObject(SELECT_SQL,
                    (rs, rowNum) -> new StepRow(rs.getLong("max_id"), rs.getLong("version")),
                    bizTag);
            if (row == null) {
                throw new IllegalStateException("biz_tag not initialized in t_id_segment: " + bizTag);
            }
            int updated = jdbcTemplate.update(UPDATE_SQL, step, bizTag, row.version());
            if (updated == 1) {
                return new Segment(row.maxId(), row.maxId() + step);
            }
            // updated == 0 说明被别的实例抢先改了 version，重读重试
        }
        throw new IllegalStateException("allocate segment failed after " + MAX_RETRY
                + " retries, bizTag=" + bizTag);
    }

    private record StepRow(long maxId, long version) {
    }
}
