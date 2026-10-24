package com.shortlink.admin.config;

import com.shortlink.common.cache.CacheKeys;
import com.shortlink.component.bloom.BloomFilter;
import com.shortlink.component.bloom.RedisBloomFilter;
import com.shortlink.component.codec.ShortCodeCodec;
import com.shortlink.component.idgen.DbSegmentAllocator;
import com.shortlink.component.idgen.SegmentIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 短链核心组件装配。
 *
 * <p>三个组件都是<b>无状态单例</b>，可以放心复用：</p>
 * <ul>
 *   <li>codec 只读密钥，纯函数；</li>
 *   <li>idgen 内部有号段游标，自带锁；</li>
 *   <li>bloom 只是 Redis 位图的操作封装，本身不存状态。</li>
 * </ul>
 */
@Configuration
public class ShortLinkCoreConfig {

    @Bean
    public ShortCodeCodec shortCodeCodec(@Value("${shortlink.codec.key}") long key) {
        return new ShortCodeCodec(key);
    }

    @Bean
    public SegmentIdGenerator segmentIdGenerator(
            JdbcTemplate jdbcTemplate,
            @Value("${shortlink.idgen.biz-tag:short_code}") String bizTag,
            @Value("${shortlink.idgen.step:1000}") int step) {
        return new SegmentIdGenerator(bizTag, step, new DbSegmentAllocator(jdbcTemplate));
    }

    @Bean
    public BloomFilter shortCodeBloomFilter(
            StringRedisTemplate redisTemplate,
            @Value("${shortlink.bloom.expected-elements:10000000}") long expectedElements) {
        return new RedisBloomFilter(redisTemplate, CacheKeys.BLOOM_CODE, expectedElements);
    }
}
