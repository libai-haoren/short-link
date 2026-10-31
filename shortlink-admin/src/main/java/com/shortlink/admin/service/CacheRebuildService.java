package com.shortlink.admin.service;

import com.shortlink.admin.domain.dto.RebuildResult;
import com.shortlink.admin.domain.entity.ShortLinkDO;
import com.shortlink.admin.support.LinkCacheWriter;
import com.shortlink.component.bloom.BloomFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 缓存重建：从 MySQL 重新灌一遍 Redis。
 *
 * <p>为什么必须有这个能力：redirect 连不上 DB，Redis 就是它唯一的数据源。
 * Redis 重启、被 FLUSH、或大范围 LRU 淘汰之后，**全站短链都会 404**。
 * 这不是理论风险——compose 里 Redis 默认就是无持久化的。</p>
 *
 * <p>实现上的三个注意点：</p>
 * <ul>
 *   <li><b>分页扫描</b>，不一次 load 全表：100 万条短链会把 admin 的堆打爆；</li>
 *   <li><b>逐条写但不逐条广播</b>：几万次 PUBLISH 会把频道和其他实例打爆，
 *       结束之后统一发一次"全部失效"；</li>
 *   <li><b>失败不中断</b>：单条写失败计数后继续，最后把 failed 返回给调用方判断。</li>
 * </ul>
 *
 * <p>重建期间的并发写可能让缓存短暂落后，但结束时的 {@code EVICT_ALL} 会把所有 L1 清掉，
 * 下一次读一律回到 Redis。</p>
 */
@Service
public class CacheRebuildService {

    private static final Logger log = LoggerFactory.getLogger(CacheRebuildService.class);

    private static final int MIN_BATCH = 100;
    private static final int MAX_BATCH = 5000;

    private final LinkPersistenceService persistenceService;
    private final LinkCacheWriter cacheWriter;
    private final BloomFilter bloomFilter;

    public CacheRebuildService(LinkPersistenceService persistenceService,
                               LinkCacheWriter cacheWriter,
                               BloomFilter bloomFilter) {
        this.persistenceService = persistenceService;
        this.cacheWriter = cacheWriter;
        this.bloomFilter = bloomFilter;
    }

    /**
     * 全量重建。
     *
     * <p>同步执行：10 万条预计 10~30s。再大的规模应该改成异步任务 + 进度查询，
     * 这里不做是因为个人项目的数据量到不了那个级别。</p>
     */
    public RebuildResult rebuild(int batchSize) {
        int size = Math.min(MAX_BATCH, Math.max(MIN_BATCH, batchSize));
        long start = System.currentTimeMillis();

        long cursor = 0L;
        long scanned = 0L;
        long written = 0L;
        long failed = 0L;

        while (true) {
            List<ShortLinkDO> rows = persistenceService.pageForRebuild(cursor == 0L ? null : cursor, size);
            if (rows.isEmpty()) {
                break;
            }
            List<String> codes = new ArrayList<>(rows.size());
            for (ShortLinkDO row : rows) {
                scanned++;
                if (cacheWriter.writeOnly(row)) {
                    written++;
                } else {
                    failed++;
                }
                codes.add(row.getShortCode());
            }
            bloomFilter.addAll(codes);
            cursor = rows.get(rows.size() - 1).getId();
        }

        // 先开闸门再通知失效：此刻 Redis 已灌满，未命中可以安全地判 404。
        // 即使顺序反过来也无副作用，但先置位能缩短"数据齐了却还在返回 503"的窗口。
        cacheWriter.markCacheReady();
        // 统一通知：版本号 +1 让漏收消息的实例也能自愈，EVICT_ALL 清掉所有 L1
        cacheWriter.markCacheChanged();
        cacheWriter.publishEvictAll();

        long elapsed = System.currentTimeMillis() - start;
        log.info("cache rebuild finished: scanned={}, written={}, failed={}, elapsed={}ms",
                scanned, written, failed, elapsed);
        return new RebuildResult(scanned, written, failed, elapsed);
    }
}
