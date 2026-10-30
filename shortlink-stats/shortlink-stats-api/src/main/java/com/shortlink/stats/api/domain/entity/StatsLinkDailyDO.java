package com.shortlink.stats.api.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 短链日聚合（{@code shortlink_stats.t_stats_link_daily}）。
 *
 * <p>这张表是"报表快照"，由 T+1 的聚合任务从 ClickHouse 回填。
 * 查询端读它而不是直接打 ClickHouse：报表查询的 QPS 可能很高，
 * 而 ClickHouse 更适合大范围扫描，不适合高频点查。</p>
 */
@Data
@TableName("t_stats_link_daily")
public class StatsLinkDailyDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String shortCode;

    private String gid;

    private LocalDate statDate;

    /** 访问次数 */
    private Long pv;

    /** 去重访客（HLL 估算） */
    private Long uv;

    /** 精确 UV（ClickHouse uniqExact），T+1 回填；为 0 表示还没回填 */
    private Long uvExact;

    private Long ipCnt;

    private Long newUv;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
