package com.shortlink.admin.support;

import com.shortlink.admin.domain.entity.ShortLinkDO;
import com.shortlink.common.cache.CacheChannels;
import com.shortlink.common.cache.CacheKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 短链缓存写入 + 变更通知。
 *
 * <p>四个关键决策：</p>
 *
 * <ol>
 *   <li><b>写时更新，不是删除。</b>redirect 连不上 MySQL，如果这里只 DEL，
 *       下一次跳转就会因为查不到而 404。这是短链场景与普通 Cache Aside 最大的区别。</li>
 *   <li><b>TTL 加随机抖动。</b>批量导入的短链 TTL 完全一致，会在同一秒集体失效。</li>
 *   <li><b>写完之后必须通知。</b>不通知的话，redirect 的 L1 要等 10 分钟 TTL 才更新。</li>
 *   <li><b>写入带版本号，且由 Lua 比较。</b>两个并发写同一条短链时，DB 层是"后提交的赢"，
 *       但两个 HSET 到达 Redis 的顺序可能与提交顺序相反——不做比较的话 Redis 会永久停在
 *       旧值，直到下一次写入或 TTL。版本号取自 {@code sl:cache:ver}（每次写 +1 的单调计数器），
 *       只有更大的版本才允许覆盖。</li>
 * </ol>
 *
 * <p>字段与 redirect 侧读取的 Hash 结构必须严格一致，改动时两边一起改。</p>
 */
@Component
public class LinkCacheWriter {

    private static final Logger log = LoggerFactory.getLogger(LinkCacheWriter.class);

    /** 基础 TTL。改链靠失效通知，TTL 只是最后一道保险。 */
    private static final Duration BASE_TTL = Duration.ofHours(24);

    /** 抖动上限 = 基础 TTL 的 10%。 */
    private static final long JITTER_SECONDS = BASE_TTL.toSeconds() / 10;

    /** 就绪标志的写入值；消费方只判断 key 是否存在，值本身不参与逻辑。 */
    private static final String CACHE_READY_VALUE = "1";

    /**
     * 写入脚本：只有版本号更大才覆盖，并同时续期。
     *
     * <p>用 Lua 是为了让"比较 + 写入 + 续期"成为一个原子操作。分开做的话，
     * 两个写之间仍会插进对方的比较，等于没加版本号。</p>
     *
     * <p>ARGV：1 version / 2 shortCode / 3 longUrl / 4 status / 5 expireTime /
     * 6 maxClick / 7 domainId / 8 ttlSeconds</p>
     */
    private static final RedisScript<Long> WRITE_SCRIPT = new DefaultRedisScript<>("""
            local incoming = tonumber(ARGV[1])
            local current = redis.call('HGET', KEYS[1], 'version')
            if current and tonumber(current) >= incoming then
                return 0
            end
            redis.call('HSET', KEYS[1],
                    'version', ARGV[1],
                    'shortCode', ARGV[2],
                    'longUrl', ARGV[3],
                    'status', ARGV[4],
                    'expireTime', ARGV[5],
                    'maxClick', ARGV[6],
                    'domainId', ARGV[7])
            redis.call('EXPIRE', KEYS[1], ARGV[8])
            return 1
            """, Long.class);

    /** 一次写入的结果。 */
    private enum WriteOutcome {
        /** 写入成功 */
        WRITTEN,
        /** 缓存里已有不低于本次的版本，说明有更新的写入先落地了——不是错误 */
        STALE,
        /** 写失败（重试耗尽） */
        FAILED
    }

    private final StringRedisTemplate redisTemplate;
    private final int writeRetry;
    private final Counter writeFailure;

    public LinkCacheWriter(StringRedisTemplate redisTemplate,
                           @Value("${shortlink.admin.cache.write-retry:3}") int writeRetry,
                           MeterRegistry meterRegistry) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.writeRetry = Math.max(1, writeRetry);
        this.writeFailure = Counter.builder("shortlink.cache.write.failure")
                .description("缓存写入最终失败次数，非零需关注（会导致 redirect 读到旧值）")
                .register(meterRegistry);
    }

    /**
     * 递增版本号 → 带版本写入缓存 → 广播失效。重复调用是安全的。
     *
     * <p>顺序不能反：先把新值（带更高版本）落进 Redis，再通知别人失效。
     * 反过来，被通知的实例回源读到的是还没更新的旧值，又回填进 L1，而且不会再有第二次通知。</p>
     *
     * <p>即使写入被判定为 STALE（有更新的版本先落地）或 FAILED，也仍然发通知：
     * 版本号已经 +1，不发的话失效通道对账会把它当成"丢了一条消息"而全量清空 L1——
     * 用一次多余的失效换掉一次全量清空，是划算的。</p>
     */
    public void put(ShortLinkDO link) {
        long version;
        try {
            version = bumpVersion();
        } catch (RuntimeException ex) {
            writeFailure.increment();
            log.error("bump cache version failed, code={}", link.getShortCode(), ex);
            return;
        }

        WriteOutcome outcome = write(link, version);
        if (outcome == WriteOutcome.STALE) {
            log.info("cache write skipped, newer version already present: code={}, version={}",
                    link.getShortCode(), version);
        }

        try {
            publishEvict(link.getShortCode());
        } catch (RuntimeException ex) {
            // 消息丢了由版本对账兜底：INCR 已发生但没收到消息，下一轮会全清
            log.warn("publish evict failed, code={}: {}", link.getShortCode(), ex.toString());
        }
    }

    /**
     * 只写 Redis，不递增版本号也不发通知。
     *
     * <p>给重建流程用：重建时会写几万条，逐条广播会把频道打爆；版本号在重建开始时
     * 通过 {@link #currentVersion()} 取一次，整批复用。</p>
     *
     * @param version 本次写入使用的版本号
     * @return true 表示缓存里已经是本次或更新的值（STALE 也算 true：不需要再写了）
     */
    public boolean writeOnly(ShortLinkDO link, long version) {
        return write(link, version) != WriteOutcome.FAILED;
    }

    private WriteOutcome write(ShortLinkDO link, long version) {
        List<String> args = List.of(
                String.valueOf(version),
                link.getShortCode(),
                link.getLongUrl(),
                String.valueOf(link.getStatus() == null ? 1 : link.getStatus()),
                // 0 表示永久；用 epoch millis 避免时区问题
                link.getExpireTime() == null
                        ? "0"
                        : String.valueOf(link.getExpireTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
                String.valueOf(link.getMaxClick() == null ? 0L : link.getMaxClick()),
                String.valueOf(link.getDomainId() == null ? 1L : link.getDomainId()),
                String.valueOf(jitteredTtl().toSeconds()));

        RuntimeException last = null;
        for (int attempt = 1; attempt <= writeRetry; attempt++) {
            try {
                Long accepted = redisTemplate.execute(WRITE_SCRIPT,
                        List.of(CacheKeys.link(link.getShortCode())), args.toArray());
                return accepted != null && accepted == 1L ? WriteOutcome.WRITTEN : WriteOutcome.STALE;
            } catch (RuntimeException ex) {
                last = ex;
                log.warn("write cache failed, code={}, attempt={}/{}",
                        link.getShortCode(), attempt, writeRetry);
            }
        }
        writeFailure.increment();
        log.error("write cache gave up, code={}, redirect may serve stale value until TTL",
                link.getShortCode(), last);
        return WriteOutcome.FAILED;
    }

    /**
     * 递增全局版本号并返回新值。
     *
     * <p>这个计数器同时干两件事：给缓存条目排序（CAS）、给失效通道对账（版本增量 vs 实收消息数）。</p>
     */
    public long bumpVersion() {
        Long value = redisTemplate.opsForValue().increment(CacheKeys.CACHE_VERSION);
        return value == null ? 0L : value;
    }

    /** 递增全局版本号（不关心具体值）。 */
    public void markCacheChanged() {
        bumpVersion();
    }

    /** 当前版本号；读失败或不存在按 0 处理，让重建仍然能写入。 */
    public long currentVersion() {
        try {
            String raw = redisTemplate.opsForValue().get(CacheKeys.CACHE_VERSION);
            return raw == null || raw.isBlank() ? 0L : Long.parseLong(raw.trim());
        } catch (RuntimeException ex) {
            log.warn("read cache version failed, assume 0: {}", ex.toString());
            return 0L;
        }
    }

    /**
     * 标记缓存已就绪：从这一刻起，redirect 才允许把"未命中"解释成"短链不存在"。
     *
     * <p>只能在全量数据已经灌进 Redis 之后调用（重建完成、或确认库里本来就没有短链）。
     * 标志跟着 Redis 一起存活：进程重启不丢，Redis 被 FLUSH 就丢——正好对应
     * "数据还在"和"数据没了"两种语义。</p>
     */
    public void markCacheReady() {
        redisTemplate.opsForValue().set(CacheKeys.CACHE_READY, CACHE_READY_VALUE);
    }

    /** 就绪标志是否在位。启动引导用它判断要不要告警。 */
    public boolean isCacheReady() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(CacheKeys.CACHE_READY));
    }

    /** 广播"这个短码失效了"。 */
    public void publishEvict(String shortCode) {
        redisTemplate.convertAndSend(CacheChannels.LINK_EVICT, shortCode);
    }

    /** 广播"全部失效"（重建后使用）。 */
    public void publishEvictAll() {
        redisTemplate.convertAndSend(CacheChannels.LINK_EVICT, CacheChannels.EVICT_ALL);
    }

    private static Duration jitteredTtl() {
        long extra = JITTER_SECONDS <= 0 ? 0 : ThreadLocalRandom.current().nextLong(JITTER_SECONDS + 1);
        return BASE_TTL.plusSeconds(extra);
    }
}
