package com.shortlink.common.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * IP 哈希：统计里只需要"能区分访客"，不需要知道访客是谁。
 *
 * <p>为什么要哈希：UV 计算必须能把同一个 IP 归并、把不同 IP 区分开，
 * 但明文 IP 属于个人信息，落到分析库里既不合规也没必要。</p>
 *
 * <p>为什么加盐：不加盐的 IP 哈希是可以被彩虹表反查的（IPv4 空间只有 2^32），
 * 加一个只有服务端知道的盐之后才有意义。盐必须配在配置中心，不能进代码库。</p>
 *
 * <p>为什么是 SHA-256 前 8 字节而不是 xxhash64：JDK 自带，零依赖。
 * 代价是慢一倍左右（纳秒级 vs 十纳秒级），在"只有开了统计才计算"的前提下可以接受。</p>
 */
public final class IpHasher {

    private IpHasher() {
    }

    /**
     * @return 非负的 64 位哈希（保证能放进 ClickHouse 的 UInt64）
     */
    public static long hash(String ip, String salt) {
        String normalizedIp = ip == null ? "" : ip;
        String normalizedSalt = salt == null ? "" : salt;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((normalizedSalt + '|' + normalizedIp).getBytes(StandardCharsets.UTF_8));
            long hash = 0L;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (bytes[i] & 0xFFL);
            }
            // 清掉符号位：ClickHouse 的 UInt64 不接受负数
            return hash & 0x7FFF_FFFF_FFFF_FFFFL;
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必须提供的算法，走不到这里
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
