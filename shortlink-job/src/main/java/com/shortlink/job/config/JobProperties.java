package com.shortlink.job.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 定时任务配置。
 *
 * @param enabled            总开关：本地只想跑业务时关掉，避免半夜被定时任务打醒
 * @param retentionDays      过期后再保留多少天才清理（留出误操作恢复窗口）
 * @param batchSize          单批处理条数
 * @param maxBatches         单次执行最多几批；跑不完下次继续，避免一次跑太久
 * @param auditRetentionDays 审计日志保留天数
 * @param lockTtl            任务锁的 TTL，必须大于任务最长执行时间
 */
@ConfigurationProperties(prefix = "shortlink.job")
public record JobProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30") int retentionDays,
        @DefaultValue("500") int batchSize,
        @DefaultValue("200") int maxBatches,
        @DefaultValue("180") int auditRetentionDays,
        @DefaultValue("5m") Duration lockTtl) {
}
