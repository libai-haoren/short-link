package com.shortlink.stats.consumer.service;

import com.shortlink.common.mq.event.ClickEvent;
import com.shortlink.stats.consumer.support.ClickRow;
import com.shortlink.stats.consumer.support.UserAgentParser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 点击事件的处理流水线：去重 → 解析 → 落库 → 实时计数。
 *
 * <p>四个依赖都抽成接口，是为了让这段逻辑能脱离 Kafka / ClickHouse / Redis 单测——
 * 这条链路的正确性（尤其是去重与幂等）不该靠"起一整套环境"来验证。</p>
 *
 * <p><b>顺序不能换</b>：必须先做去重再写库，否则 Kafka 重投递会把 PV 算高；
 * 而实时计数放在最后，是因为它丢一两次无所谓，落库失败才需要重试。</p>
 */
public class ClickProcessor {

    private static final Logger log = LoggerFactory.getLogger(ClickProcessor.class);

    private final DedupGuard dedupGuard;
    private final ClickSink sink;
    private final RealtimeCounter realtimeCounter;
    private final Counter processedCounter;
    private final Counter duplicatedCounter;

    public ClickProcessor(DedupGuard dedupGuard,
                          ClickSink sink,
                          RealtimeCounter realtimeCounter,
                          MeterRegistry meterRegistry) {
        this.dedupGuard = dedupGuard;
        this.sink = sink;
        this.realtimeCounter = realtimeCounter;
        this.processedCounter = Counter.builder("shortlink.click.processed")
                .tag("result", "ok").register(meterRegistry);
        this.duplicatedCounter = Counter.builder("shortlink.click.processed")
                .tag("result", "duplicate").register(meterRegistry);
    }

    /**
     * 处理一条事件。
     *
     * <p>重复事件直接返回 true（视为"已处理"），让调用方照常提交位点——
     * 否则一条重复消息会导致无限重试。</p>
     *
     * @return true 表示这条消息可以提交位点
     */
    public boolean handle(ClickEvent event) {
        if (event == null || event.shortCode() == null || event.shortCode().isBlank()) {
            log.warn("skip invalid click event: {}", event);
            return true;
        }
        if (!dedupGuard.firstSeen(event.eventId())) {
            duplicatedCounter.increment();
            return true;
        }

        ClickRow row = toRow(event);
        try {
            sink.accept(row);
        } catch (RuntimeException ex) {
            // 落库失败：先撤掉去重标记，再返回 false 让消费端不要提交位点。
            // 不撤标记的话，重投的消息会被判重直接跳过，数据就静默丢了。
            dedupGuard.forget(event.eventId());
            log.error("write click row failed, eventId={}", event.eventId(), ex);
            return false;
        }

        try {
            realtimeCounter.increment(event.shortCode(), event.eventTime(), event.ipHash());
        } catch (RuntimeException ex) {
            // 实时计数失败不影响落库，也不应该触发重投（否则明细会重复）
            log.warn("realtime counter failed, shortCode={}: {}", event.shortCode(), ex.toString());
        }

        processedCounter.increment();
        return true;
    }

    /** 事件 → 明细行。公开出来是为了能单独对字段映射做断言。 */
    public static ClickRow toRow(ClickEvent event) {
        UserAgentParser.ParsedUa ua = UserAgentParser.parse(event.userAgent());
        return new ClickRow(
                event.eventTime(),
                event.shortCode(),
                "",                 // gid 事件体里没有，V5 再从短码反查补全
                event.ipHash(),
                "", "", "",         // 地域：需要 IP 库，V3 只留字段
                ua.device(),
                ua.os(),
                ua.browser(),
                event.referer() == null ? "" : event.referer(),
                event.userAgent() == null ? "" : event.userAgent(),
                event.traceId() == null ? "" : event.traceId(),
                ua.bot() ? 1 : 0);
    }
}
