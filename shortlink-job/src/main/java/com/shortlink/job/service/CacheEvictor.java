package com.shortlink.job.service;

/**
 * 短链缓存失效。
 *
 * <p>抽成接口是为了让清理服务能脱离 Redis 单测——"删完必须失效缓存"是这段逻辑里
 * 最容易被漏掉、也最难在线上发现的一步（表现为"库里删了但用户还能跳"）。</p>
 */
public interface CacheEvictor {

    void evict(String shortCode);
}
