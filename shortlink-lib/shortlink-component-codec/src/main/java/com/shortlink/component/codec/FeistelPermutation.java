package com.shortlink.component.codec;

/**
 * 42 bit 域上的 Feistel 置换（4 轮，21/21 分组）。
 *
 * <p>作用：把连续的发号映射成无序的伪随机数，既保持一一对应（双射、可逆），又让人无法按顺序遍历短码。
 * 轮函数不需要可逆，这是 Feistel 结构最大的便利。</p>
 *
 * <pre>
 * 加密：L,R = R, L ^ F(R, r)          r = 0..3
 * 解密：L,R = R ^ F(L, r), L          r = 3..0
 * </pre>
 *
 * <p>安全性说明：4 轮足以抵抗“相邻号推断规律”这种弱攻击，但不是密码学级 FPE。
 * 工业级可用 NIST SP 800-38G 的 FF1；本项目选择自实现，是为了能用 30 行讲清楚原理（面试价值 &gt; 加密强度）。</p>
 */
public final class FeistelPermutation {

    public static final int BITS = 42;
    public static final long SPACE = 1L << BITS;

    private static final int HALF_BITS = BITS / 2;
    private static final long HALF_MASK = (1L << HALF_BITS) - 1;
    private static final int ROUNDS = 4;

    /** 黄金比例常数，用于把轮次与半区打散。 */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long MIX = 0xBF58476D1CE4E5B9L;

    private final long key;

    public FeistelPermutation(long key) {
        this.key = key;
    }

    public long encrypt(long value) {
        checkRange(value);
        long left = value >>> HALF_BITS;
        long right = value & HALF_MASK;
        for (int round = 0; round < ROUNDS; round++) {
            long nextLeft = right;
            long nextRight = left ^ roundFunction(right, round);
            left = nextLeft;
            right = nextRight;
        }
        return (left << HALF_BITS) | right;
    }

    public long decrypt(long value) {
        checkRange(value);
        long left = value >>> HALF_BITS;
        long right = value & HALF_MASK;
        for (int round = ROUNDS - 1; round >= 0; round--) {
            long prevLeft = right ^ roundFunction(left, round);
            long prevRight = left;
            left = prevLeft;
            right = prevRight;
        }
        return (left << HALF_BITS) | right;
    }

    /** F(R, r) 取 21 bit，无需可逆。 */
    private long roundFunction(long half, int round) {
        long z = key + half * GOLDEN + round * MIX;
        return mix(z) & HALF_MASK;
    }

    /** SplitMix64 finalizer：雪崩性好、无分支、单次乘法链。 */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static void checkRange(long value) {
        if (value < 0 || value >= SPACE) {
            throw new IllegalArgumentException("value out of 42-bit space: " + value);
        }
    }
}
