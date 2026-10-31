package com.shortlink.redirect.cache;

/**
 * 缓存就绪状态：Redis 里的数据是否足以把"未命中"当成"真的不存在"。
 *
 * <p>抽成接口是为了让 {@code LinkResolver} 不依赖 Redis 生命周期对象，
 * 单测里可以直接用 lambda 造出"就绪 / 未就绪"两种状态。</p>
 */
public interface CacheReadiness {

    /**
     * @return true 表示缓存数据完整，未命中可以判 404；false 表示数据可能缺失，应判 503
     */
    boolean isReady();
}
