package com.shortlink.stats.consumer.support;

import java.util.Locale;

/**
 * User-Agent 解析。
 *
 * <p>为什么自己写而不是上 ua-parser：这里只需要"设备/OS/浏览器"三个粗粒度维度，
 * 一个精确到版本号的重型库（百万级正则）换不来统计价值，反而会成为消费瓶颈。
 * 需要精确版本时再引入，接口已经隔离好了。</p>
 *
 * <p><b>判定顺序有讲究</b>，写错了结果会互相污染：</p>
 * <ul>
 *   <li>微信的 UA 里同时含 {@code MicroMessenger} 和 {@code Safari}，不先判微信就会算成 Safari；</li>
 *   <li>Edge 含 {@code Chrome}，Chrome 含 {@code Safari}，所以顺序必须是 Edge → Chrome → Safari；</li>
 *   <li>iPad 的桌面模式 UA 与 Mac 几乎一样，只能靠 {@code iPad} 关键字兜。</li>
 * </ul>
 */
public final class UserAgentParser {

    public static final String DEVICE_PC = "PC";
    public static final String DEVICE_MOBILE = "MOBILE";
    public static final String DEVICE_TABLET = "TABLET";
    public static final String DEVICE_UNKNOWN = "UNKNOWN";

    public static final String OS_WINDOWS = "Windows";
    public static final String OS_MACOS = "macOS";
    public static final String OS_IOS = "iOS";
    public static final String OS_ANDROID = "Android";
    public static final String OS_LINUX = "Linux";
    public static final String OS_UNKNOWN = "Unknown";

    public static final String BROWSER_WECHAT = "WeChat";
    public static final String BROWSER_EDGE = "Edge";
    public static final String BROWSER_CHROME = "Chrome";
    public static final String BROWSER_FIREFOX = "Firefox";
    public static final String BROWSER_SAFARI = "Safari";
    public static final String BROWSER_IE = "IE";
    public static final String BROWSER_UNKNOWN = "Unknown";

    /** 空 UA 一律按未知处理，而不是猜成 PC。 */
    private static final String[] BOT_TOKENS = {
            "bot", "spider", "crawler", "crawl", "slurp", "bingpreview",
            "curl/", "wget", "python-requests", "java/", "okhttp", "headlesschrome", "phantomjs"
    };

    private UserAgentParser() {
    }

    /**
     * @param userAgent 原始 UA，可为 null
     */
    public static ParsedUa parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new ParsedUa(DEVICE_UNKNOWN, OS_UNKNOWN, BROWSER_UNKNOWN, false);
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        return new ParsedUa(device(ua), os(ua), browser(ua), isBot(ua));
    }

    private static boolean isBot(String ua) {
        for (String token : BOT_TOKENS) {
            if (ua.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String device(String ua) {
        // 先判平板：iPad 的 UA 里也含 "Mobile"，顺序反了会被算成手机
        if (ua.contains("ipad") || ua.contains("tablet") || ua.contains("kindle")) {
            return DEVICE_TABLET;
        }
        if (ua.contains("mobile") || ua.contains("iphone") || ua.contains("android")
                || ua.contains("windows phone")) {
            return DEVICE_MOBILE;
        }
        if (ua.contains("windows") || ua.contains("macintosh") || ua.contains("x11")
                || ua.contains("linux")) {
            return DEVICE_PC;
        }
        return DEVICE_UNKNOWN;
    }

    private static String os(String ua) {
        if (ua.contains("windows phone")) {
            return "Windows Phone";
        }
        if (ua.contains("windows nt") || ua.contains("windows")) {
            return OS_WINDOWS;
        }
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("cpu os")) {
            return OS_IOS;
        }
        if (ua.contains("android")) {
            return OS_ANDROID;
        }
        if (ua.contains("mac os x") || ua.contains("macintosh")) {
            return OS_MACOS;
        }
        if (ua.contains("linux")) {
            return OS_LINUX;
        }
        return OS_UNKNOWN;
    }

    private static String browser(String ua) {
        // 顺序不能换：后面几个的关键字都被前面的包含
        if (ua.contains("micromessenger")) {
            return BROWSER_WECHAT;
        }
        if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/")) {
            return BROWSER_EDGE;
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("firefox/") || ua.contains("fxios/")) {
            return BROWSER_FIREFOX;
        }
        if (ua.contains("msie") || ua.contains("trident/")) {
            return BROWSER_IE;
        }
        if (ua.contains("chrome/") || ua.contains("crios/")) {
            return BROWSER_CHROME;
        }
        if (ua.contains("safari/")) {
            return BROWSER_SAFARI;
        }
        return BROWSER_UNKNOWN;
    }

    /**
     * @param device  PC / MOBILE / TABLET / UNKNOWN
     * @param os      操作系统
     * @param browser 浏览器
     * @param bot     是否爬虫（统计 PV/UV 时剔除）
     */
    public record ParsedUa(String device, String os, String browser, boolean bot) {
    }
}
