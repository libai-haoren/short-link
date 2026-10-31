package com.shortlink.redirect.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本号缺口判定。
 *
 * <p>这组用例锁的是改造的核心：<b>正常写入不能清 L1</b>。
 * 改造前是"版本一变就全清"，每次 admin 写一条短链，所有实例的 10 万条缓存全没，
 * 精确失效形同虚设。</p>
 */
class VersionGapTrackerTest {

    @Test
    @DisplayName("首次轮询只立基线，不触发全清")
    void firstPollOnlyEstablishesBaseline() {
        VersionGapTracker tracker = new VersionGapTracker();

        assertFalse(tracker.onPoll(42, 0));
    }

    @Test
    @DisplayName("正常写入：版本与消息 1:1 → 什么都不清")
    void healthyWritesDoNotEvictAnything() {
        VersionGapTracker tracker = new VersionGapTracker();
        tracker.onPoll(42, 0);

        assertFalse(tracker.onPoll(43, 1));
        assertFalse(tracker.onPoll(44, 1));
    }

    @Test
    @DisplayName("消息只是晚到一轮 → 不清（改造前误清的元凶）")
    void delayedMessageIsNotTreatedAsLoss() {
        VersionGapTracker tracker = new VersionGapTracker();
        tracker.onPoll(42, 0);

        // INCR 已经生效，PUBLISH 的消息还没被订阅线程处理
        assertFalse(tracker.onPoll(43, 0), "第一轮发现缺口应当先观察");

        // 下一轮消息到了，版本没再涨
        assertFalse(tracker.onPoll(43, 1), "补上之后不应全清");
    }

    @Test
    @DisplayName("真丢消息：连续两轮补不上才清")
    void realLossEvictsAfterSecondPoll() {
        VersionGapTracker tracker = new VersionGapTracker();
        tracker.onPoll(42, 0);

        assertFalse(tracker.onPoll(43, 0));
        assertTrue(tracker.onPoll(43, 0));
    }

    @Test
    @DisplayName("版本回退（Redis 被 FLUSH）立即全清")
    void versionResetEvictsImmediately() {
        VersionGapTracker tracker = new VersionGapTracker();
        tracker.onPoll(42, 0);

        assertTrue(tracker.onPoll(0, 0));
    }

    @Test
    @DisplayName("多轮累积的缺口也能被补齐，不会误判")
    void accumulatedGapCanStillBeRecovered() {
        VersionGapTracker tracker = new VersionGapTracker();
        tracker.onPoll(42, 0);

        assertFalse(tracker.onPoll(45, 1));   // 期望 3 收到 1，缺 2
        assertFalse(tracker.onPoll(45, 2));   // 补上这 2 条
    }

    @Test
    @DisplayName("补齐之后不会带着旧缺口继续误判")
    void recoveryClearsCarriedShortfall() {
        VersionGapTracker tracker = new VersionGapTracker();
        tracker.onPoll(42, 0);

        tracker.onPoll(43, 0);                // 缺口 1，挂起
        assertFalse(tracker.onPoll(43, 1));   // 补齐
        assertFalse(tracker.onPoll(44, 1));   // 之后恢复正常
    }
}
