package com.shortlink.common.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpHasherTest {

    @Test
    @DisplayName("同样的 IP + 盐必须得到同样的哈希（UV 归并的前提）")
    void deterministic() {
        assertEquals(IpHasher.hash("1.2.3.4", "salt-a"), IpHasher.hash("1.2.3.4", "salt-a"));
    }

    @Test
    @DisplayName("不同盐得到不同哈希（防彩虹表）")
    void saltMatters() {
        assertNotEquals(IpHasher.hash("1.2.3.4", "salt-a"), IpHasher.hash("1.2.3.4", "salt-b"));
    }

    @Test
    @DisplayName("不同 IP 得到不同哈希")
    void differentIpsDiffer() {
        assertNotEquals(IpHasher.hash("1.2.3.4", "s"), IpHasher.hash("1.2.3.5", "s"));
    }

    @Test
    @DisplayName("结果必须非负，否则放进 ClickHouse 的 UInt64 会溢出")
    void alwaysNonNegative() {
        String[] ips = {"0.0.0.0", "255.255.255.255", "192.168.1.1", "::1", "2001:db8::1", ""};
        for (String ip : ips) {
            long hash = IpHasher.hash(ip, "salt");
            assertTrue(hash >= 0, "hash of " + ip + " is negative: " + hash);
        }
    }

    @Test
    @DisplayName("null 不抛异常")
    void nullSafe() {
        assertTrue(IpHasher.hash(null, null) >= 0);
    }
}
