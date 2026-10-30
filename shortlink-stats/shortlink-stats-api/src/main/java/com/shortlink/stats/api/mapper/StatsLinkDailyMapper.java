package com.shortlink.stats.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shortlink.stats.api.domain.entity.StatsLinkDailyDO;

/**
 * 日聚合查询。
 *
 * <p>读多写少，且只按 {@code (short_code, stat_date)} 走唯一索引，用 BaseMapper 足够，
 * 不手写 SQL 反而少了拼错列名的风险。</p>
 */
public interface StatsLinkDailyMapper extends BaseMapper<StatsLinkDailyDO> {
}
