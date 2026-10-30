package com.shortlink.redirect.cache;

import reactor.core.publisher.Mono;

/**
 * 短链读缓存。
 *
 * <p>语义约定（调用方依赖这三条语义，不要在实现里改）：</p>
 * <ul>
 *   <li>返回<b>空 Mono</b>：短码不存在（正常的 404）；</li>
 *   <li>返回 {@link LinkSnapshot}：命中；</li>
 *   <li>返回 error：{@link CorruptCacheDataException} 数据损坏，{@link CacheUnavailableException} 依赖故障。</li>
 * </ul>
 *
 * <p>把"不存在"与"查不到"分开，是这条链路最重要的设计：前者回 404，后者必须回 503。</p>
 *
 * <p>{@link #invalidate} 用于响应 admin 的变更通知：只清本进程的副本，
 * 不去动 Redis——Redis 是数据源，admin 已经写过了。</p>
 */
public interface LinkCache {

    Mono<LinkSnapshot> get(String shortCode);

    /** 让某个短码的本地副本失效。纯 Redis 实现可以是空操作。 */
    void invalidate(String shortCode);

    /** 让全部本地副本失效。用于版本号变化与缓存重建后的兜底。 */
    void invalidateAll();
}
