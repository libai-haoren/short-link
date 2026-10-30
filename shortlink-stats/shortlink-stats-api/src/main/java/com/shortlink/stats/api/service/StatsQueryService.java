package com.shortlink.stats.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shortlink.common.cache.CacheKeys;
import com.shortlink.common.core.exception.BizException;
import com.shortlink.common.core.result.ErrorCode;
import com.shortlink.stats.api.domain.dto.LinkStatsView;
import com.shortlink.stats.api.domain.dto.RealtimeView;
import com.shortlink.stats.api.domain.entity.StatsLinkDailyDO;
import com.shortlink.stats.api.mapper.StatsLinkDailyMapper;
import com.shortlink.stats.api.support.DailyPoint;
import com.shortlink.stats.api.support.DailySeries;
import com.shortlink.stats.api.support.RealtimePoint;
import com.shortlink.stats.api.support.StatsRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 统计查询。
 *
 * <p>数据来源分三处，各有分工：</p>
 * <ul>
 *   <li><b>MySQL</b>（{@code t_stats_link_daily}）：T+1 的日聚合，报表的主体；</li>
 *   <li><b>Redis</b>：今天的实时值，弥补"日聚合还没跑"的空窗；</li>
 *   <li><b>ClickHouse</b>：明细与多维 TopN（本版本未接入）。</li>
 * </ul>
 *
 * <p>Redis 故障时降级为"只有 DB 数据"，而不是整个接口 500——
 * 报表少今天的数远好过整页打不开。</p>
 */
@Service
public class StatsQueryService {

    private static final Logger log = LoggerFactory.getLogger(StatsQueryService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_CODE_LENGTH = 16;

    private final StatsLinkDailyMapper mapper;
    private final StringRedisTemplate redisTemplate;

    public StatsQueryService(StatsLinkDailyMapper mapper, StringRedisTemplate redisTemplate) {
        this.mapper = mapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 日趋势。
     *
     * @param from 为空默认 7 天前
     * @param to   为空默认今天
     */
    public LinkStatsView query(String shortCode, LocalDate from, LocalDate to) {
        String code = requireCode(shortCode);
        LocalDate today = LocalDate.now(ZONE);
        StatsRange range = StatsRange.of(from, to, today);

        List<StatsLinkDailyDO> rows = mapper.selectList(new LambdaQueryWrapper<StatsLinkDailyDO>()
                .eq(StatsLinkDailyDO::getShortCode, code)
                .between(StatsLinkDailyDO::getStatDate, range.from(), range.to())
                .orderByAsc(StatsLinkDailyDO::getStatDate));

        Map<LocalDate, RealtimePoint> realtime = readRealtimeQuietly(code, range, today);
        List<DailyPoint> points = DailySeries.fill(rows, range, today, realtime);
        return LinkStatsView.of(code, points, range.from(), range.to());
    }

    /** 今日实时指标。 */
    public RealtimeView realtime(String shortCode) {
        String code = requireCode(shortCode);
        LocalDate today = LocalDate.now(ZONE);
        String date = today.toString();
        return new RealtimeView(code, today, readPvQuietly(code, date), readUvQuietly(code, date));
    }

    /**
     * 只读今天的实时值：其它日期已经有聚合结果，没必要多打两次 Redis。
     */
    private Map<LocalDate, RealtimePoint> readRealtimeQuietly(String code, StatsRange range, LocalDate today) {
        if (today.isBefore(range.from()) || today.isAfter(range.to())) {
            return Map.of();
        }
        String date = today.toString();
        long pv = readPvQuietly(code, date);
        long uv = readUvQuietly(code, date);
        if (pv <= 0 && uv <= 0) {
            return Map.of();
        }
        return Map.of(today, new RealtimePoint(pv, uv));
    }

    private long readPvQuietly(String code, String date) {
        try {
            String raw = redisTemplate.opsForValue().get(CacheKeys.statsPv(code, date));
            return raw == null ? 0L : Long.parseLong(raw.trim());
        } catch (RuntimeException ex) {
            log.warn("read realtime pv failed, code={}, date={}: {}", code, date, ex.toString());
            return 0L;
        }
    }

    private long readUvQuietly(String code, String date) {
        try {
            Long size = redisTemplate.opsForHyperLogLog().size(CacheKeys.statsUv(code, date));
            return size == null ? 0L : size;
        } catch (RuntimeException ex) {
            log.warn("read realtime uv failed, code={}, date={}: {}", code, date, ex.toString());
            return 0L;
        }
    }

    private static String requireCode(String shortCode) {
        if (shortCode == null || shortCode.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "shortCode 不能为空");
        }
        String code = shortCode.trim();
        if (code.length() > MAX_CODE_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID, "shortCode 非法");
        }
        return code;
    }
}
