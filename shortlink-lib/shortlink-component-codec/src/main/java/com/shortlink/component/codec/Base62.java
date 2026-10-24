package com.shortlink.component.codec;

import java.util.Arrays;

/**
 * Base62 编解码。
 *
 * <p>字母表 {@code 0-9A-Za-z}，共 62 个字符。<b>大小写敏感</b>，短码比较、索引、路由都必须区分大小写
 * （MySQL 默认 collation 是大小写不敏感的，建表时 short_code 列需显式 {@code utf8mb4_bin} 或查询用 BINARY）。</p>
 *
 * <p>纯算法类，无任何依赖：可被 admin（生成）与工具类复用，也可单独做性能基准。</p>
 */
public final class Base62 {

    public static final int BASE = 62;

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /** 反查表：ASCII 128 以内直接查表，避免每次遍历字母表。 */
    private static final int[] INDEX = new int[128];

    /** long 最大值 9.22e18 < 62^11 ≈ 5.2e19，11 字符足够。 */
    private static final int MAX_CHARS = 11;

    static {
        Arrays.fill(INDEX, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            INDEX[ALPHABET[i]] = i;
        }
    }

    private Base62() {
    }

    /**
     * 编码非负 long。
     *
     * @throws IllegalArgumentException value 为负数
     */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be >= 0, got " + value);
        }
        if (value == 0) {
            return "0";
        }
        char[] buffer = new char[MAX_CHARS];
        int pos = buffer.length;
        long remaining = value;
        while (remaining > 0) {
            buffer[--pos] = ALPHABET[(int) (remaining % BASE)];
            remaining /= BASE;
        }
        return new String(buffer, pos, buffer.length - pos);
    }

    /**
     * 解码。
     *
     * @throws IllegalArgumentException 空串、非法字符、溢出
     */
    public static long decode(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        long value = 0L;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit = c < 128 ? INDEX[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("illegal base62 char: '" + c + "' at index " + i);
            }
            // 溢出检查：value * 62 + digit 超过 long 上限会变负数
            if (value > (Long.MAX_VALUE - digit) / BASE) {
                throw new IllegalArgumentException("base62 value overflow: " + text);
            }
            value = value * BASE + digit;
        }
        return value;
    }
}
