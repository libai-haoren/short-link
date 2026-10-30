package com.shortlink.admin.service;

import com.shortlink.common.cache.CacheKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * IP 黑名单维护。
 *
 * <p>存 Redis Set（{@code sl:black:ip}），由 gateway 与 redirect 各自定时拉全量到本地。
 * 不落 MySQL：黑名单是运维态数据，读多写少且允许最终一致，没必要进业务库。</p>
 */
@Service
public class BlacklistService {

    private static final int MAX_LIST_SIZE = 1000;

    private final StringRedisTemplate redisTemplate;

    public BlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** @return true 表示本次新增；false 表示本来就在名单里 */
    public boolean add(String ip) {
        Long added = redisTemplate.opsForSet().add(CacheKeys.BLACK_IP, ip);
        return added != null && added > 0;
    }

    /** @return true 表示确实移除了 */
    public boolean remove(String ip) {
        Long removed = redisTemplate.opsForSet().remove(CacheKeys.BLACK_IP, ip);
        return removed != null && removed > 0;
    }

    public List<String> list() {
        var members = redisTemplate.opsForSet().members(CacheKeys.BLACK_IP);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream().sorted(Comparator.naturalOrder()).limit(MAX_LIST_SIZE).toList();
    }
}
