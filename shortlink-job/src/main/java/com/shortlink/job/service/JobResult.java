package com.shortlink.job.service;

/**
 * 一次任务执行的结果。
 *
 * @param job       任务名
 * @param scanned   扫描/影响的行数
 * @param batches   实际执行批次数
 * @param elapsedMs 耗时
 * @param completed true 表示已处理干净；false 表示达到批次上限提前退出（下次继续）
 */
public record JobResult(String job, long scanned, int batches, long elapsedMs, boolean completed) {

    public static JobResult completed(String job, long scanned, int batches, long elapsedMs) {
        return new JobResult(job, scanned, batches, elapsedMs, true);
    }

    public static JobResult partial(String job, long scanned, int batches, long elapsedMs) {
        return new JobResult(job, scanned, batches, elapsedMs, false);
    }
}
