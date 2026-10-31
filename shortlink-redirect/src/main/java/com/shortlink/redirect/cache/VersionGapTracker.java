package com.shortlink.redirect.cache;

/**
 * 版本号缺口的判定器：判断"版本涨了，但消息没全收到"是否真的丢了。
 *
 * <p>为什么需要它：admin 的写入顺序是 <b>先 INCR 版本号、再 PUBLISH</b>。
 * 轮询线程可能在消息还没被订阅线程处理时就读到新版本，此时"版本增量 1、实际收到 0"
 * 并不代表丢消息，只是消息还在路上。直接全清会把每次正常写入都变成一次全量失效——
 * 那正是改造前的行为。</p>
 *
 * <p>所以这里做<b>一轮延迟判定</b>：第一次发现缺口只记下来，下一轮如果补上了就什么事都没有，
 * 仍然补不上才认定真丢。代价是丢失的发现时间从 1s 变成 2s，换来的是正常写入不再误清 L1。</p>
 *
 * <p>抽成独立类是为了能脱离 Redis 单测——这段逻辑有状态、边界多，
 * 靠集成测试很难覆盖全。</p>
 */
final class VersionGapTracker {

    /** 是否已经拿到过基线。第一次轮询只记录基线，不判定。 */
    private boolean baselined;

    /** 上一次轮询读到的版本号。 */
    private long baseline;

    /** 上一轮没补齐的缺口，累加到下一轮的期望值里。 */
    private long carriedShortfall;

    /**
     * 喂一次轮询结果。
     *
     * @param version               本次读到的版本号
     * @param receivedSinceLastPoll 自上次轮询以来实际收到的失效消息数
     * @return true 表示确认丢失，调用方应当清空本地缓存
     */
    boolean onPoll(long version, long receivedSinceLastPoll) {
        if (!baselined) {
            baselined = true;
            baseline = version;
            carriedShortfall = 0L;
            return false;
        }

        if (version < baseline) {
            // 版本回退只可能是 key 被删/被重置（FLUSH、主从空切）。
            // 这种情况下本地缓存必然不可信，直接清。
            baseline = version;
            carriedShortfall = 0L;
            return true;
        }

        long expected = (version - baseline) + carriedShortfall;
        baseline = version;

        if (receivedSinceLastPoll >= expected) {
            // 消息齐了（甚至多了），缺口清空
            carriedShortfall = 0L;
            return false;
        }

        long shortfall = expected - receivedSinceLastPoll;
        if (carriedShortfall > 0L) {
            // 上一轮就欠着，这一轮还没补上 → 判定为真丢
            carriedShortfall = 0L;
            return true;
        }

        // 第一次发现缺口：先等一轮，消息可能只是还没被订阅线程处理
        carriedShortfall = shortfall;
        return false;
    }
}
