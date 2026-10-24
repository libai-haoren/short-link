package com.shortlink.common.mq.event;

/**
 * 点击事件。
 *
 * <p>字段设计的两条硬约束：</p>
 * <ol>
 *   <li><b>不存明文 IP</b>：只存加盐 hash，既能算 UV 又不违反隐私要求。</li>
 *   <li><b>不存长链</b>：事件体越小，Kafka 吞吐越高；统计只需要 shortCode。</li>
 * </ol>
 *
 * <p>eventId 用于消费端幂等：Kafka 是至少一次投递，重复消息会把 PV 算高。</p>
 *
 * @param eventId   幂等键（shortCode + ipHash + 毫秒时间戳）
 * @param shortCode 短码
 * @param eventTime 事件时间（毫秒），由 redirect 生成而不是消费端生成——
 *                  否则消费积压时统计时间会整体偏移
 * @param ipHash    xxhash64(ip + salt)
 * @param userAgent 原始 UA，消费端再解析设备/OS/浏览器
 * @param referer   来源页
 * @param traceId   链路 ID，便于从统计反查请求
 */
public record ClickEvent(
        String eventId,
        String shortCode,
        long eventTime,
        long ipHash,
        String userAgent,
        String referer,
        String traceId
) {
}
