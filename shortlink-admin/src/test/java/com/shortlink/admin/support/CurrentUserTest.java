package com.shortlink.admin.support;

import com.shortlink.common.core.constant.HeaderConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份与来源 IP 的解析。
 *
 * <p>这几行代码决定"审计记的是谁"，写错了不会报错、只会记错人——
 * 所以边界（没经过网关、头被改坏、没有请求上下文）都要钉住。</p>
 */
class CurrentUserTest {

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void bind(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("没有请求上下文时返回匿名，不抛异常")
    void noContext() {
        assertTrue(CurrentUser.id().isEmpty());
        assertEquals(CurrentUser.ANONYMOUS_ID, CurrentUser.idOrAnonymous());
        assertEquals("", CurrentUser.username());
        assertEquals("", CurrentUser.clientIp());
    }

    @Test
    @DisplayName("网关注入的 X-User-Id 能读到")
    void readsGatewayHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderConstants.USER_ID, "42");
        request.addHeader("X-Username", "alice");
        bind(request);

        assertEquals(42L, CurrentUser.idOrAnonymous());
        assertEquals("alice", CurrentUser.username());
    }

    @Test
    @DisplayName("直连 admin（没有 X-User-Id）时记匿名，而不是报错")
    void missingHeaderMeansAnonymous() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        bind(request);

        assertTrue(CurrentUser.id().isEmpty());
        assertEquals(0L, CurrentUser.idOrAnonymous());
    }

    @Test
    @DisplayName("头被改成非数字时降级为匿名，绝不让写操作失败")
    void malformedHeaderDegrades() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderConstants.USER_ID, "not-a-number");
        bind(request);

        assertTrue(CurrentUser.id().isEmpty());
        assertEquals(0L, CurrentUser.idOrAnonymous());
    }

    @Test
    @DisplayName("优先取网关注入的 X-Client-Ip")
    void prefersGatewayClientIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderConstants.CLIENT_IP, "203.0.113.7");
        request.setRemoteAddr("172.20.0.1");
        bind(request);

        assertEquals("203.0.113.7", CurrentUser.clientIp());
    }

    @Test
    @DisplayName("没有 X-Client-Ip 时退化成 remote address")
    void fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.20.0.1");
        bind(request);

        assertEquals("172.20.0.1", CurrentUser.clientIp());
    }
}
