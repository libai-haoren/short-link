package com.shortlink.redirect.stats;

import com.shortlink.common.core.util.IpHasher;
import com.shortlink.common.mq.event.ClickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickEventsTest {

    private static final long NOW = 1_700_000_000_000L;

    private static ClickEvent build(String ip, String userAgent, String referer) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/abc1234")
                .remoteAddress(new InetSocketAddress(ip, 12345));
        if (userAgent != null) {
            builder.header(HttpHeaders.USER_AGENT, userAgent);
        }
        if (referer != null) {
            builder.header(HttpHeaders.REFERER, referer);
        }
        return ClickEvents.build(MockServerWebExchange.from(builder.build()), "abc1234", "salt", NOW);
    }

    @Test
    @DisplayName("IP 只以加盐哈希形式出现，事件体里没有明文")
    void hashesIp() {
        ClickEvent event = build("1.2.3.4", "Mozilla/5.0", "https://example.com");

        assertEquals(IpHasher.hash("1.2.3.4", "salt"), event.ipHash());
        assertEquals("abc1234", event.shortCode());
        assertEquals(NOW, event.eventTime());
    }

    @Test
    @DisplayName("eventId 包含短码/IP/时间，用于消费端去重")
    void eventIdShape() {
        ClickEvent event = build("1.2.3.4", null, null);

        assertTrue(event.eventId().startsWith("abc1234:"));
        assertTrue(event.eventId().endsWith(":" + NOW));
    }

    @Test
    @DisplayName("超长 UA / referer 被截断，避免撑爆 Kafka 单条消息")
    void truncatesLongHeaders() {
        String longUa = "x".repeat(2000);
        String longReferer = "y".repeat(2000);

        ClickEvent event = build("1.2.3.4", longUa, longReferer);

        assertEquals(512, event.userAgent().length());
        assertEquals(512, event.referer().length());
    }

    @Test
    @DisplayName("缺失的 UA / referer 归一成空串而不是 null")
    void missingHeadersBecomeEmpty() {
        ClickEvent event = build("1.2.3.4", null, null);

        assertNotNull(event.userAgent());
        assertEquals("", event.userAgent());
        assertEquals("", event.referer());
    }

    @Test
    @DisplayName("取不到 remote address 时不抛异常")
    void handlesMissingRemoteAddress() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/abc1234").build();
        ClickEvent event = ClickEvents.build(MockServerWebExchange.from(request), "abc1234", "salt", NOW);

        assertEquals(IpHasher.hash("", "salt"), event.ipHash());
    }
}
