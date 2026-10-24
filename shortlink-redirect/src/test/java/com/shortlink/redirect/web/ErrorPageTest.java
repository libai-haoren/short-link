package com.shortlink.redirect.web;

import com.shortlink.redirect.service.ResolveResult.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorPageTest {

    @Test
    @DisplayName("渲染完整 HTML 文档")
    void rendersHtmlDocument() {
        String html = new String(ErrorPage.render(Outcome.NOT_FOUND), StandardCharsets.UTF_8);

        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("<meta charset=\"utf-8\">"));
        assertTrue(html.contains("链接不存在"));
        assertTrue(html.contains("</html>"));
    }

    @Test
    @DisplayName("每种错误结果都渲染出对应文案")
    void rendersPerOutcomeMessage() {
        assertTrue(render(Outcome.EXPIRED).contains("已过期"));
        assertTrue(render(Outcome.DISABLED).contains("已停用"));
        assertTrue(render(Outcome.UNAVAILABLE).contains("服务暂时不可用"));
        assertTrue(render(Outcome.INVALID).contains("不存在"));
        assertTrue(render(Outcome.NOT_FOUND).contains("不存在"));
    }

    @Test
    @DisplayName("状态码映射与设计一致")
    void mapsStatusCodes() {
        assertEquals(404, ErrorPage.statusOf(Outcome.NOT_FOUND));
        assertEquals(404, ErrorPage.statusOf(Outcome.INVALID));
        assertEquals(403, ErrorPage.statusOf(Outcome.DISABLED));
        assertEquals(410, ErrorPage.statusOf(Outcome.EXPIRED));
        assertEquals(503, ErrorPage.statusOf(Outcome.UNAVAILABLE));
    }

    @Test
    @DisplayName("页面不引用任何外部资源")
    void noExternalResources() {
        String html = render(Outcome.NOT_FOUND);
        assertFalse(html.contains("http://"));
        assertFalse(html.contains("https://"));
        assertFalse(html.contains("<script"));
    }

    private static String render(Outcome outcome) {
        return new String(ErrorPage.render(outcome), StandardCharsets.UTF_8);
    }
}
