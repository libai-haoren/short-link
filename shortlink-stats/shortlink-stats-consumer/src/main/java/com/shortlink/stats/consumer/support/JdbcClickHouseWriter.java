package com.shortlink.stats.consumer.support;

import com.shortlink.stats.consumer.service.ClickHouseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * 用 JDBC 批量写 ClickHouse。
 *
 * <p>故意<b>不依赖 clickhouse-jdbc 的任何类型</b>：只用标准 {@code DataSource} + {@code PreparedStatement}。
 * 好处是消费端的业务代码零 ClickHouse 依赖，驱动只在运行时存在；
 * 将来换 HTTP 接口或者 Kafka Engine 表，这个类整个换掉即可。</p>
 *
 * <p>SQL 由 {@link ClickRow#COLUMNS} 拼出来，保证"列数 = 占位符数"，不会出现
 * 手工维护两处列表导致错位的问题。</p>
 */
public class JdbcClickHouseWriter implements ClickHouseWriter {

    private static final Logger log = LoggerFactory.getLogger(JdbcClickHouseWriter.class);

    private static final String TABLE = "shortlink.link_click_detail";

    static final String INSERT_SQL = buildInsertSql();

    private final DataSource dataSource;

    public JdbcClickHouseWriter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    static String buildInsertSql() {
        String columns = String.join(", ", ClickRow.COLUMNS);
        String placeholders = String.join(", ", java.util.Collections.nCopies(ClickRow.COLUMNS.length, "?"));
        return "INSERT INTO " + TABLE + " (" + columns + ") VALUES (" + placeholders + ")";
    }

    @Override
    public void write(List<ClickRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        long start = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {

            for (ClickRow row : rows) {
                bind(statement, row);
                statement.addBatch();
            }
            statement.executeBatch();
            log.debug("wrote {} click rows in {}ms", rows.size(), System.currentTimeMillis() - start);
        } catch (SQLException ex) {
            throw new IllegalStateException("write clickhouse failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * 绑定参数，顺序必须与 {@link ClickRow#COLUMNS} 完全一致（含 0 基与 1 基的差别）。
     */
    static void bind(PreparedStatement statement, ClickRow row) throws SQLException {
        int index = 1;
        // event_time: DateTime64(3) —— 用 Timestamp 传毫秒精度
        statement.setTimestamp(index++, new Timestamp(row.eventTime()));
        statement.setString(index++, row.shortCode());
        statement.setString(index++, row.gid());
        statement.setLong(index++, row.ipHash());
        statement.setString(index++, row.country());
        statement.setString(index++, row.province());
        statement.setString(index++, row.city());
        statement.setString(index++, row.device());
        statement.setString(index++, row.os());
        statement.setString(index++, row.browser());
        statement.setString(index++, row.referer());
        statement.setString(index++, row.userAgent());
        statement.setString(index++, row.traceId());
        statement.setInt(index++, row.isBot());
    }
}
