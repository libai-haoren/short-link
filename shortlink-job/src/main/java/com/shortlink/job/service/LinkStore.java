package com.shortlink.job.service;

import java.time.LocalDateTime;

/**
 * 过期短链的存储访问。
 *
 * <p>抽成接口不是为了"将来换数据库"，而是为了让清理循环（分批、上限、缓存失效）
 * 能脱离 MySQL 单测——这段逻辑最容易写成死循环。</p>
 */
public interface LinkStore {

    /**
     * 取一批"已过期且未删除"的短链。
     *
     * @param expiredBefore 过期时间早于这个时刻才算（留出保留期）
     * @param limit         本批最多取多少条
     */
    java.util.List<ExpiredLink> findExpired(LocalDateTime expiredBefore, int limit);

    /**
     * 标记删除（软删）。
     *
     * @return 实际影响行数
     */
    int markDeleted(java.util.List<Long> ids);

    /**
     * @param id        主键
     * @param shortCode 短码（用于失效缓存）
     */
    record ExpiredLink(Long id, String shortCode) {
    }
}
