package com.shortlink.redirect.cache;

import java.util.List;

/**
 * 短链快照：redirect 跳转所需的全部字段。
 *
 * <p>字段顺序与 {@link RedisLinkCache} 的 HMGET 请求顺序一一对应，
 * 改动时必须同步修改 {@code RedisLinkCache#FIELDS} 与 admin 的
 * {@code LinkCacheWriter}。这三处任何一处漏改，表现都是"线上 404"或"503 数据损坏"，
 * 而且只有部分短链会触发，极难排查。</p>
 *
 * @param longUrl          目标长链，含 query
 * @param status           1 启用；其它值一律按停用处理（fail-closed）
 * @param expireTimeMillis 0 表示永不过期；否则为 epoch 毫秒
 * @param maxClick         0 表示不限次数
 * @param domainId         短链域名，redirect 不使用，保留以便排障
 * @param version          写入版本号，用于拒绝"迟到的旧值"覆盖新值；0 表示历史数据
 */
public record LinkSnapshot(
        String longUrl,
        int status,
        long expireTimeMillis,
        long maxClick,
        long domainId,
        long version
) {

    /**
     * 从 HMGET 的返回列表解析。顺序：longUrl, status, expireTime, maxClick, domainId, version。
     *
     * <p>前五个字段全部严格校验、不给默认值：admin 侧总是写全，出现 null 说明写入方与
     * 读取方的契约已经不一致，这种情况必须暴露而不是猜。</p>
     *
     * <p><b>version 是唯一例外</b>：它是后加的字段，加版本之前写入的历史数据没有这一项，
     * 解析成 0 而不是抛异常——否则上线那一刻所有老缓存都会变成"数据损坏 503"。
     * 按 0 处理后，任何一次新写入（版本从 1 起）都会覆盖它。</p>
     *
     * @throws CorruptCacheDataException 字段缺失或数字格式非法
     */
    public static LinkSnapshot parse(List<String> values) {
        if (values == null || values.size() < 5) {
            throw new CorruptCacheDataException(
                    "expect at least 5 fields but got " + (values == null ? 0 : values.size()));
        }
        String longUrl = values.get(0);
        if (longUrl == null || longUrl.isBlank()) {
            throw new CorruptCacheDataException("field 'longUrl' is missing");
        }
        return new LinkSnapshot(
                longUrl,
                parseInt(values.get(1), "status"),
                parseLong(values.get(2), "expireTime"),
                parseLong(values.get(3), "maxClick"),
                parseLong(values.get(4), "domainId"),
                parseVersion(values.size() > 5 ? values.get(5) : null));
    }

    private static int parseInt(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new CorruptCacheDataException("field '" + field + "' is missing");
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new CorruptCacheDataException("field '" + field + "' is not a number: " + raw, ex);
        }
    }

    private static long parseLong(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new CorruptCacheDataException("field '" + field + "' is missing");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new CorruptCacheDataException("field '" + field + "' is not a number: " + raw, ex);
        }
    }

    /** 版本缺失或不可解析一律按 0 处理：它只用于排序，读错最多是"被覆盖一次"，不值得让整条链路报损坏。 */
    private static long parseVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
