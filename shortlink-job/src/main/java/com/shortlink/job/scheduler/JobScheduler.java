package com.shortlink.job.scheduler;

import com.shortlink.job.config.JobProperties;
import com.shortlink.job.lock.JobLock;
import com.shortlink.job.service.AuditArchiveService;
import com.shortlink.job.service.JobResult;
import com.shortlink.job.service.LinkCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 定时调度。
 *
 * <p>定时触发和手动触发（{@link com.shortlink.job.controller.JobController}）走同一个
 * {@link #runLocked} 入口，保证"同一时刻只有一个实例在跑"这条约束对两条路径都成立。</p>
 *
 * <p>cron 都放在凌晨：清理任务会更新大量行，放在业务高峰就是自找麻烦。</p>
 */
@Component
public class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobLock jobLock;
    private final LinkCleanupService linkCleanupService;
    private final AuditArchiveService auditArchiveService;
    private final JobProperties properties;

    public JobScheduler(JobLock jobLock,
                        LinkCleanupService linkCleanupService,
                        AuditArchiveService auditArchiveService,
                        JobProperties properties) {
        this.jobLock = jobLock;
        this.linkCleanupService = linkCleanupService;
        this.auditArchiveService = auditArchiveService;
        this.properties = properties;
    }

    /** 每天 03:30 清理过期短链。 */
    @Scheduled(cron = "${shortlink.job.cleanup-cron:0 30 3 * * ?}")
    public void scheduledCleanup() {
        runLocked(LinkCleanupService.JOB_NAME, () -> linkCleanupService.cleanup(LocalDateTime.now()));
    }

    /** 每天 04:00 归档审计日志。 */
    @Scheduled(cron = "${shortlink.job.audit-cron:0 0 4 * * ?}")
    public void scheduledAuditArchive() {
        runLocked(AuditArchiveService.JOB_NAME, () -> auditArchiveService.archive(LocalDateTime.now()));
    }

    /**
     * 带锁执行。
     *
     * @return executed=false 表示别的实例正在跑
     */
    public JobRunView runLocked(String jobName, Supplier<JobResult> task) {
        if (!properties.enabled()) {
            log.debug("job disabled by config: {}", jobName);
            return new JobRunView(false, null, "任务总开关已关闭");
        }
        if (!jobLock.tryLock(jobName)) {
            log.info("job skipped, another instance holds the lock: {}", jobName);
            return new JobRunView(false, null, "已有实例在执行");
        }
        try {
            return new JobRunView(true, task.get(), null);
        } finally {
            jobLock.unlock(jobName);
        }
    }

    /**
     * @param executed 是否真的执行了
     * @param result   执行结果（未执行时为 null）
     * @param reason   未执行的原因
     */
    public record JobRunView(boolean executed, JobResult result, String reason) {
    }
}
