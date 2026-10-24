-- =====================================================================
--  ShortLink · ClickHouse Schema（V3 使用）
--  适用：ClickHouse 23.x / 24.x
--  执行：clickhouse-client --multiquery < sql/03-clickhouse.sql
-- =====================================================================

CREATE DATABASE IF NOT EXISTS shortlink;

DROP TABLE IF EXISTS shortlink.link_click_detail;

CREATE TABLE shortlink.link_click_detail
(
    event_time   DateTime64(3, 'Asia/Shanghai')       COMMENT '事件时间(服务端)',
    short_code   LowCardinality(String)               COMMENT '短码',
    gid          String                               COMMENT '业务ID',
    ip_hash      UInt64                               COMMENT 'xxhash64(ip+salt)，不存明文',
    country      LowCardinality(String) DEFAULT '',
    province     LowCardinality(String) DEFAULT '',
    city         LowCardinality(String) DEFAULT '',
    device       LowCardinality(String) DEFAULT ''    COMMENT 'PC/MOBILE/TABLET',
    os           LowCardinality(String) DEFAULT '',
    browser      LowCardinality(String) DEFAULT '',
    referer      String DEFAULT '',
    user_agent   String DEFAULT '',
    trace_id     String DEFAULT '',
    is_bot       UInt8 DEFAULT 0                      COMMENT '爬虫标记，统计时剔除'
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(event_time)
ORDER BY (short_code, event_time)
TTL event_time + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- 日聚合物化视图
--
-- 坑点：不能用 SummingMergeTree 存 uv —— uniqExact 不是可加状态，
-- 跨分区合并会算错。必须用 AggregatingMergeTree + uniqState/uniqMerge。
DROP TABLE IF EXISTS shortlink.mv_link_daily;

CREATE MATERIALIZED VIEW shortlink.mv_link_daily
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(stat_date)
ORDER BY (stat_date, short_code)
AS
SELECT
    toDate(event_time)        AS stat_date,
    short_code,
    countState()              AS pv_state,
    uniqState(ip_hash)        AS uv_state
FROM shortlink.link_click_detail
WHERE is_bot = 0
GROUP BY stat_date, short_code;

-- 查询示例（Merge 后缀不能漏）：
-- SELECT stat_date,
--        short_code,
--        countMerge(pv_state) AS pv,
--        uniqMerge(uv_state)  AS uv
-- FROM shortlink.mv_link_daily
-- WHERE stat_date = today() - 1
-- GROUP BY stat_date, short_code
-- ORDER BY pv DESC
-- LIMIT 20;
