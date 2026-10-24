package com.shortlink.common.core.util;

import java.security.SecureRandom;

/**
 * ULID 生成器：48 bit 毫秒时间戳 + 80 bit 随机数，Crockford Base32 编码为 26 字符。
 *
 * <p>为什么用它而不是 UUID / 雪花：</p>
 * <ul>
 *   <li>UUID 随机无序，做 MySQL 主键会导致页分裂；ULID 前缀是时间，插入近似顺序。</li>
 *   <li>雪花需要机器号分配，多实例部署要多一份协调；ULID 无状态。</li>
 *   <li>字符串形式可直接对外暴露，不泄露自增 ID 与业务量。</li>
 * </ul>
 *
 * <p>局限：同一毫秒内不保证单调递增（需要单调版本需维护 lastTimestamp + 随机数自增），
 * 本项目用作业务 ID，不承担排序职责，故不实现。</p>
 */
public final class Ulid {

    /** Crockford Base32：去掉了 I、L、O、U，避免肉眼混淆。 */
    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int TIME_CHARS = 10;
    private static final int RANDOM_CHARS = 16;

    private Ulid() {
    }

    public static String next() {
        return next(System.currentTimeMillis());
    }

    public static String next(long epochMillis) {
        if (epochMillis < 0) {
            throw new IllegalArgumentException("epochMillis must be >= 0");
        }
        char[] out = new char[TIME_CHARS + RANDOM_CHARS];
        encodeTime(epochMillis, out);
        encodeRandom(out);
        return new String(out);
    }

    /** 48 bit 时间戳 -> 10 个字符（50 bit，最高 2 bit 恒为 0）。 */
    private static void encodeTime(long time, char[] out) {
        long value = time;
        for (int i = TIME_CHARS - 1; i >= 0; i--) {
            out[i] = ENCODING[(int) (value & 0x1F)];
            value >>>= 5;
        }
    }

    /** 80 bit 随机数 -> 16 个字符（80 / 5 = 16，无填充）。 */
    private static void encodeRandom(char[] out) {
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        int buffer = 0;
        int bits = 0;
        int index = TIME_CHARS + RANDOM_CHARS - 1;
        for (int i = random.length - 1; i >= 0; i--) {
            buffer |= (random[i] & 0xFF) << bits;
            bits += 8;
            while (bits >= 5) {
                out[index--] = ENCODING[buffer & 0x1F];
                buffer >>>= 5;
                bits -= 5;
            }
        }
    }

    /** 从 ULID 中解析出毫秒时间戳，便于排障时直接看出生成时间。 */
    public static long timestamp(String ulid) {
        if (ulid == null || ulid.length() != TIME_CHARS + RANDOM_CHARS) {
            throw new IllegalArgumentException("invalid ulid: " + ulid);
        }
        long value = 0L;
        for (int i = 0; i < TIME_CHARS; i++) {
            int digit = decode(ulid.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("invalid ulid char: " + ulid.charAt(i));
            }
            value = (value << 5) | digit;
        }
        return value;
    }

    private static int decode(char c) {
        for (int i = 0; i < ENCODING.length; i++) {
            if (ENCODING[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
