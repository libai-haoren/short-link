package com.shortlink.admin.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时引导缓存就绪标志 {@code sl:cache:ready}。
 *
 * <p>背景：redirect 靠这个标志区分"短码不存在"（404）和"缓存数据没了"（503）。
 * 标志由全量重建写入，但如果一个<b>空库</b>刚部署、从没跑过重建，标志永远不存在，
 * 那么用户访问任何不存在的短码都会收到 503"服务暂时不可用"——这是错误的。
 * 空库没有数据需要预热，可以安全置位。</p>
 *
 * <p>反过来，<b>库里有数据但标志缺失</b>说明 Redis 被清空过（FLUSH / 主从空切），
 * 这时绝不能置位，否则等于把"数据丢了"重新贴上"数据在"的标签。
 * 只打一条 WARN，等运维调 {@code POST /api/v1/admin/cache/rebuild}。</p>
 *
 * <p>整个引导失败不阻断启动：多打一条告警，比让 admin 起不来好。</p>
 */
@Component
public class CacheReadinessBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CacheReadinessBootstrap.class);

    private final JdbcTemplate jdbcTemplate;
    private final LinkCacheWriter cacheWriter;
    private final boolean enabled;

    public CacheReadinessBootstrap(JdbcTemplate jdbcTemplate,
                                   LinkCacheWriter cacheWriter,
                                   @Value("${shortlink.admin.cache.ready-bootstrap:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.cacheWriter = cacheWriter;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("cache readiness bootstrap disabled");
            return;
        }
        try {
            if (cacheWriter.isCacheReady()) {
                log.info("cache readiness flag already present, skip bootstrap");
                return;
            }
            if (hasAnyLink()) {
                log.warn("cache readiness flag is absent while t_short_link is not empty: "
                        + "redirect will answer 503 for every cache miss until "
                        + "POST /api/v1/admin/cache/rebuild is called");
                return;
            }
            // 空库：没有短链需要预热，直接置位，避免把"查不存在的码"误判成服务不可用
            cacheWriter.markCacheReady();
            log.info("t_short_link is empty, cache readiness flag initialized");
        } catch (RuntimeException ex) {
            log.warn("cache readiness bootstrap skipped: {}", ex.toString());
        }
    }

    /** 只探测"有没有至少一行"，不用 COUNT(*)：大表上 COUNT 是全表扫。 */
    private boolean hasAnyLink() {
        Integer one = jdbcTemplate.query("SELECT 1 FROM t_short_link LIMIT 1",
                rs -> rs.next() ? Integer.valueOf(1) : null);
        return one != null;
    }
}
