package com.shortlink.job.service;

import java.util.List;

/**
 * 短链缓存失效。
 *
 * <p>抽成接口是为了让清理服务能脱离 Redis 单测——"删完必须失效缓存"是这段逻辑里
 * 最容易被漏掉、也最难在线上发现的一步（表现为"库里删了但用户还能跳"）。</p>
 */
public interface CacheEvictor {

    /** 失效单个短码。 */
    void evict(String shortCode);

    /**
     * 批量失效。
     *
     * <p>默认逐条调用，实现方可以覆盖成一次 pipeline——清理任务动辄删几百条，
     * 逐条走三个命令（DEL / INCR / PUBLISH）会把一次夜间任务放大成几千次往返。</p>
     *
     * @param shortCodes 待失效的短码，允许为空列表
     */
    default void evictBatch(List<String> shortCodes) {
        for (String shortCode : shortCodes) {
            evict(shortCode);
        }
    }
}
