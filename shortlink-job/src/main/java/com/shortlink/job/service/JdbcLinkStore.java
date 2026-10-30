package com.shortlink.job.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 JdbcTemplate 的短链表访问。
 *
 * <p>两个 SQL 都刻意写了 {@code ORDER BY id LIMIT ?}：
 * 没有 ORDER BY 的 LIMIT 在 InnoDB 上语义不确定，分批清理时可能反复取到同一批行，
 * 表现为"任务跑完但数据没少"。</p>
 */
public class JdbcLinkStore implements LinkStore {

    private static final String SELECT_EXPIRED = """
            SELECT id, short_code
              FROM t_short_link
             WHERE del_flag = 0
               AND expire_time IS NOT NULL
               AND expire_time < ?
             ORDER BY id
             LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcLinkStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ExpiredLink> findExpired(LocalDateTime expiredBefore, int limit) {
        return jdbcTemplate.query(SELECT_EXPIRED,
                (rs, rowNum) -> new ExpiredLink(rs.getLong("id"), rs.getString("short_code")),
                Timestamp.valueOf(expiredBefore), limit);
    }

    @Override
    public int markDeleted(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        // 占位符按 ids 数量动态生成；用 String.join 而不是拼接用户输入，不存在注入
        List<String> placeholders = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            placeholders.add("?");
        }
        String sql = "UPDATE t_short_link SET del_flag = 1, update_time = NOW() WHERE del_flag = 0 AND id IN ("
                + String.join(",", placeholders) + ")";
        return jdbcTemplate.update(sql, ids.toArray());
    }
}
