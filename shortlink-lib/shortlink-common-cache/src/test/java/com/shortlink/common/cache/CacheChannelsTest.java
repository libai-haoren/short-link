package com.shortlink.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存同步的线协议：载荷要么是短码，要么是通配符 {@code *}。
 *
 * <p>Base62 字母表不含 {@code *}，所以两者不会歧义——这条测试就是守着这个前提。</p>
 */
class CacheChannelsTest {

    @Test
    @DisplayName("通配符判定")
    void detectsEvictAll() {
        assertTrue(CacheChannels.isEvictAll(CacheChannels.EVICT_ALL));
        assertTrue(CacheChannels.isEvictAll("*"));
    }

    @Test
    @DisplayName("正常短码不会被误判成通配符")
    void normalCodeIsNotEvictAll() {
        assertFalse(CacheChannels.isEvictAll("aB3xK9z"));
        assertFalse(CacheChannels.isEvictAll("0000000"));
        assertFalse(CacheChannels.isEvictAll(""));
        assertFalse(CacheChannels.isEvictAll(null));
    }

    @Test
    @DisplayName("Base62 字母表确实不含星号")
    void base62HasNoStar() {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        assertFalse(alphabet.contains(CacheChannels.EVICT_ALL));
    }
}
