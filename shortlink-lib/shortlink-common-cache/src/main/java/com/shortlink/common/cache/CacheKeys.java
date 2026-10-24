package com.shortlink.common.cache;

/**
 * Redis key 的唯一出口。
 *
 * <p>为什么集中管理：key 散落在各处时，改前缀、查大 key、算内存占用都无从下手。
 * 这里与《详细设计》4.5 的表格一一对应，新增 key 先改文档再加方法。</p>
 *
 * <p>命名约定：{@code sl:{业务}:{维度}:{标识}}，全部小写；禁止在业务代码里拼字符串。</p>
 */
public final class CacheKeys {

    private static final String PREFIX = "sl:";

    /** 短链元数据 Hash，field: longUrl/status/expireTime/maxClick/domainId。 */
    public static final String LINK_PREFIX = PREFIX + "link:";

    /** 空值缓存，防穿透。 */
    public static final String LINK_NULL_PREFIX = PREFIX + "link:null:";

    /** 短码布隆过滤器（Bitmap）。 */
    public static final String BLOOM_CODE = PREFIX + "bloom:code";

    /** 黑名单 IP 布隆过滤器。 */
    public static final String BLOOM_IP = PREFIX + "bloom:ip";

    /** 精确 IP 黑名单（布隆误判时二次确认）。 */
    public static final String BLACK_IP = PREFIX + "black:ip";

    /** 恶意域名黑名单。 */
    public static final String BLACK_DOMAIN = PREFIX + "black:domain";

    /** 全局缓存版本号：MQ 丢消息时的兜底。 */
    public static final String CACHE_VERSION = PREFIX + "cache:ver";

    private CacheKeys() {
    }

    public static String link(String shortCode) {
        return LINK_PREFIX + shortCode;
    }

    public static String linkNull(String shortCode) {
        return LINK_NULL_PREFIX + shortCode;
    }

    /** 网关侧 IP 分钟级计数。 */
    public static String ipRate(String ip, String minute) {
        return PREFIX + "rate:ip:" + ip + ":" + minute;
    }

    /** redirect 侧单短码秒级计数。 */
    public static String codeRate(String shortCode, String second) {
        return PREFIX + "rate:code:" + shortCode + ":" + second;
    }

    /** 实时 PV（看板秒级刷新用，最终以 ClickHouse 为准）。 */
    public static String pv(String shortCode, String yyyyMMdd) {
        return PREFIX + "pv:" + shortCode + ":" + yyyyMMdd;
    }

    /** 实时 UV（HyperLogLog，误差约 0.81%）。 */
    public static String uv(String shortCode, String yyyyMMdd) {
        return PREFIX + "uv:" + shortCode + ":" + yyyyMMdd;
    }

    /** 发号器号段兜底（DB 抖动时用）。 */
    public static String segment(String bizTag) {
        return PREFIX + "seg:" + bizTag;
    }

    /** 接口幂等令牌。 */
    public static String idempotent(String key) {
        return PREFIX + "idem:" + key;
    }
}
