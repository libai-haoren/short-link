package com.shortlink.job.controller;

import com.shortlink.common.core.result.Result;
import com.shortlink.job.scheduler.JobScheduler;
import com.shortlink.job.service.AuditArchiveService;
import com.shortlink.job.service.LinkCleanupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务管理接口。
 *
 * <p>为什么需要手动触发：定时任务"跑没跑对"很难验证——等到凌晨三点看结果不现实。
 * 有了这几个接口，联调和排障时可以立刻跑一次并看到结果。</p>
 *
 * <p>生产上这些接口应该只允许网关访问（走 JWT），当前直接暴露在 job 的端口上。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/jobs")
public class JobController {

    private final JobScheduler scheduler;
    private final LinkCleanupService linkCleanupService;
    private final AuditArchiveService auditArchiveService;

    public JobController(JobScheduler scheduler,
                         LinkCleanupService linkCleanupService,
                         AuditArchiveService auditArchiveService) {
        this.scheduler = scheduler;
        this.linkCleanupService = linkCleanupService;
        this.auditArchiveService = auditArchiveService;
    }

    /** 手动触发：过期短链清理。 */
    @PostMapping("/expired-cleanup")
    public Result<JobScheduler.JobRunView> cleanup() {
        return Result.ok(scheduler.runLocked(LinkCleanupService.JOB_NAME,
                () -> linkCleanupService.cleanup(LocalDateTime.now())));
    }

    /** 手动触发：审计日志归档。 */
    @PostMapping("/audit-archive")
    public Result<JobScheduler.JobRunView> auditArchive() {
        return Result.ok(scheduler.runLocked(AuditArchiveService.JOB_NAME,
                () -> auditArchiveService.archive(LocalDateTime.now())));
    }

    /** 列出可执行的任务。 */
    @GetMapping
    public Result<List<String>> list() {
        return Result.ok(List.of(LinkCleanupService.JOB_NAME, AuditArchiveService.JOB_NAME));
    }
}
