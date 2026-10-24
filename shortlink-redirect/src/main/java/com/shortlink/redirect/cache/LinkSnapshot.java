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
 * @param maxClick         0 表示不限次数（V1 只解析，不执行）
 * @param domainId         短链域名，redirect 不使用，保留以便排障
 */
public record LinkSnapshot(
        String longUrl,
        int status,
        long expireTimeMillis,
        long maxClick,
        long domainId
) {

    /**
     * 从 HMGET 的返回列表解析。顺序：longUrl, status, expireTime, maxClick, domainId。
     *
     * <p>刻意对五个字段全部严格校验，不给默认值：admin 侧总是写全五个字段，
     * 出现 null 说明写入方与读取方的契约已经不一致，这种情况必须暴露而不是猜。</p>
     *
     * @throws CorruptCacheDataException 字段缺失或数字格式非法
     */
    public static LinkSnapshot parse(List<String> values) {
        if (values == null || values.size() < 5) {
            throw new CorruptCacheDataException(
                    "expect 5 fields but got " + (values == null ? 0 : values.size()));
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
                parseLong(values.get(4), "domainId"));
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
}
