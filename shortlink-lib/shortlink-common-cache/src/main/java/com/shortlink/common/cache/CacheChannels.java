package com.shortlink.common.cache;

/**
 * 缓存同步通道（Redis Pub/Sub）。
 *
 * <p>为什么用 Pub/Sub 而不是 Kafka：缓存失效是"通知"而不是"数据流"——
 * 只需要通知所有实例"这个 key 变了"，不要求回放、不要求累积、不要求分区有序。
 * Pub/Sub 天生 fan-out，每个实例都能收到全量消息；用 Kafka 反而要为每个实例
 * 分配独立 groupId 再去 assign 全部分区，才能模拟出广播。</p>
 *
 * <p>代价是 at-most-once：实例离线期间的消息会永久丢失。这个缺口由
 * {@link CacheKeys#CACHE_VERSION} 的版本号轮询兜底（见 redirect 的 CacheVersionWatcher）。</p>
 *
 * <p><b>线协议</b>：载荷只有一个字段——短码本身；通配符 {@link #EVICT_ALL} 表示清空全部。
 * 不用 JSON 是因为 Base62 字母表不含 {@code *}，不存在歧义；真需要扩展时换 JSON 只改一个编解码类。</p>
 */
public final class CacheChannels {

    /** 本地缓存失效通知频道。 */
    public static final String LINK_EVICT = "sl:cache-sync";

    /** 清空全部本地缓存（重建后使用）。 */
    public static final String EVICT_ALL = "*";

    /**
     * 缓存版本号在 Redis 里被重置（或 Redis 被 FLUSH）时读到的空值，
     * 统一按这个值参与比较，避免 null 判断散落各处。
     */
    public static final String VERSION_FALLBACK = "0";

    private CacheChannels() {
    }

    public static boolean isEvictAll(String payload) {
        return EVICT_ALL.equals(payload);
    }
}
