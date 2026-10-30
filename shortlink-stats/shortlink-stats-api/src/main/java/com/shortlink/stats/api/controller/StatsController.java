package com.shortlink.stats.api.controller;

import com.shortlink.common.core.result.Result;
import com.shortlink.stats.api.domain.dto.LinkStatsView;
import com.shortlink.stats.api.domain.dto.RealtimeView;
import com.shortlink.stats.api.service.StatsQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 统计查询接口。
 *
 * <pre>
 * # 最近 7 天（默认区间）
 * curl 'http://127.0.0.1:8085/api/v1/stats/links/abc1234'
 *
 * # 指定区间（最长 90 天）
 * curl 'http://127.0.0.1:8085/api/v1/stats/links/abc1234?from=2026-10-01&to=2026-10-27'
 *
 * # 今日实时
 * curl 'http://127.0.0.1:8085/api/v1/stats/links/abc1234/realtime'
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final StatsQueryService statsQueryService;

    public StatsController(StatsQueryService statsQueryService) {
        this.statsQueryService = statsQueryService;
    }

    /**
     * 日趋势（PV/UV）。
     *
     * <p>区间缺省为最近 7 天；今天这一天的数据优先取 Redis 实时值。</p>
     */
    @GetMapping("/links/{shortCode}")
    public Result<LinkStatsView> linkStats(
            @PathVariable String shortCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(statsQueryService.query(shortCode, from, to));
    }

    /** 今日实时 PV/UV（UV 为 HLL 估算值）。 */
    @GetMapping("/links/{shortCode}/realtime")
    public Result<RealtimeView> realtime(@PathVariable String shortCode) {
        return Result.ok(statsQueryService.realtime(shortCode));
    }
}
