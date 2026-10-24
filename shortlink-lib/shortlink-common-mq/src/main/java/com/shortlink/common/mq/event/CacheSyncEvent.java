package com.shortlink.common.mq.event;

/**
 * 缓存同步事件：通知各 redirect 实例失效/更新本地缓存。
 *
 * <p>为什么带 {@code version}：广播消费 + 重试会导致消息乱序到达（同一 key 的旧消息后到），
 * 消费端比较版本号即可丢弃陈旧消息，避免“用旧值覆盖新值”。</p>
 *
 * <p>为什么带 {@code payload}：redirect 不能回源 DB，消息里直接带全量新值，
 * 消费端可以 put 而不是 invalidate，省掉一次 Redis 回查。</p>
 *
 * @param shortCode 短码
 * @param operation UPSERT / DELETE
 * @param version   单调递增版本号，由 admin 在写库时生成
 * @param payload   最新值（DELETE 时为 null）
 */
public record CacheSyncEvent(
        String shortCode,
        Operation operation,
        long version,
        LinkSnapshot payload
) {

    public enum Operation {
        UPSERT,
        DELETE
    }

    /**
     * 短链快照：redirect 跳转所需的全部字段。
     *
     * <p>刻意不包含 longUrl 之外的业务字段（标题、分组等），保持缓存条目小。
     */
    public record LinkSnapshot(
            String shortCode,
            String longUrl,
            int status,
            long expireTime,
            long maxClick,
            long domainId
    ) {
    }
}
