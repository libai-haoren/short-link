package com.shortlink.stats.consumer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UA 解析的判定顺序很容易写错，这里用真实 UA 串把顺序钉死。
 */
class UserAgentParserTest {

    private static final String IPHONE_SAFARI =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    private static final String ANDROID_CHROME =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final String WINDOWS_CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final String IPAD_SAFARI =
            "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    private static final String WECHAT_ANDROID =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.42";

    private static final String EDGE_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0";

    @Test
    @DisplayName("iPhone Safari")
    void iphone() {
        var ua = UserAgentParser.parse(IPHONE_SAFARI);
        assertEquals(UserAgentParser.DEVICE_MOBILE, ua.device());
        assertEquals(UserAgentParser.OS_IOS, ua.os());
        assertEquals(UserAgentParser.BROWSER_SAFARI, ua.browser());
        assertFalse(ua.bot());
    }

    @Test
    @DisplayName("Android Chrome")
    void android() {
        var ua = UserAgentParser.parse(ANDROID_CHROME);
        assertEquals(UserAgentParser.DEVICE_MOBILE, ua.device());
        assertEquals(UserAgentParser.OS_ANDROID, ua.os());
        assertEquals(UserAgentParser.BROWSER_CHROME, ua.browser());
    }

    @Test
    @DisplayName("桌面 Chrome 不能因为含 Safari 关键字被判成 Safari")
    void desktopChrome() {
        var ua = UserAgentParser.parse(WINDOWS_CHROME);
        assertEquals(UserAgentParser.DEVICE_PC, ua.device());
        assertEquals(UserAgentParser.OS_WINDOWS, ua.os());
        assertEquals(UserAgentParser.BROWSER_CHROME, ua.browser());
    }

    @Test
    @DisplayName("iPad 的 UA 含 Mobile，但必须先判成平板")
    void ipadIsTablet() {
        var ua = UserAgentParser.parse(IPAD_SAFARI);
        assertEquals(UserAgentParser.DEVICE_TABLET, ua.device());
        assertEquals(UserAgentParser.OS_IOS, ua.os());
    }

    @Test
    @DisplayName("微信 UA 含 Safari，必须先判成微信")
    void wechat() {
        var ua = UserAgentParser.parse(WECHAT_ANDROID);
        assertEquals(UserAgentParser.BROWSER_WECHAT, ua.browser());
        assertEquals(UserAgentParser.DEVICE_MOBILE, ua.device());
    }

    @Test
    @DisplayName("Edge 的 UA 含 Chrome，必须先判成 Edge")
    void edge() {
        var ua = UserAgentParser.parse(EDGE_WINDOWS);
        assertEquals(UserAgentParser.BROWSER_EDGE, ua.browser());
    }

    @Test
    @DisplayName("Firefox 桌面")
    void firefox() {
        var ua = UserAgentParser.parse("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0");
        assertEquals(UserAgentParser.BROWSER_FIREFOX, ua.browser());
        assertEquals(UserAgentParser.OS_WINDOWS, ua.os());
    }

    @Test
    @DisplayName("爬虫要能识别出来，统计 PV/UV 时会剔除")
    void bot() {
        var googlebot = UserAgentParser.parse(
                "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");
        assertTrue(googlebot.bot());

        assertTrue(UserAgentParser.parse("curl/8.4.0").bot());
        assertTrue(UserAgentParser.parse("python-requests/2.31.0").bot());
    }

    @Test
    @DisplayName("正常浏览器不能被误判成爬虫")
    void normalBrowserIsNotBot() {
        assertFalse(UserAgentParser.parse(WINDOWS_CHROME).bot());
        assertFalse(UserAgentParser.parse(IPHONE_SAFARI).bot());
    }

    @Test
    @DisplayName("空 UA 一律未知，不猜成 PC")
    void emptyUa() {
        for (String ua : new String[]{null, "", "   "}) {
            var parsed = UserAgentParser.parse(ua);
            assertEquals(UserAgentParser.DEVICE_UNKNOWN, parsed.device());
            assertEquals(UserAgentParser.OS_UNKNOWN, parsed.os());
            assertEquals(UserAgentParser.BROWSER_UNKNOWN, parsed.browser());
            assertFalse(parsed.bot());
        }
    }
}
