package com.shortlink.component.codec;

/**
 * 短码编解码器：号段号 -> 7 位短码。
 *
 * <p>核心是把“连续号段”映射成“看起来随机的 7 位串”，同时保证：</p>
 * <ol>
 *   <li><b>恰好 7 位</b>：号段初始值取 62^6，编码结果天然不补零；</li>
 *   <li><b>双射</b>：不会产生重复短码，插入时不需要重试；</li>
 *   <li><b>可逆</b>：线上排查时能把短码反解回号段号，与 DB 对账；</li>
 *   <li><b>不可枚举</b>：不能用 aaaaaa1、aaaaaa2 顺序爬全站。</li>
 * </ol>
 *
 * <p>实现要点：Feistel 在 42 bit 全域上是双射，但可用码空间只有 {@code 62^7 - 62^6} 个（不是 2 的幂），
 * 所以要用<b>循环行走</b>（cycle-walking）：落在非法区间的结果再加密一次，直到落入合法区间。
 * 因为合法区间占全域约 78.8%，期望迭代次数 ≈ 1.27，开销可忽略。</p>
 */
public final class ShortCodeCodec {

    /** 62^6，号段起始值。保证编码后总是 7 位。 */
    public static final long MIN_ID = 56_800_235_584L;

    /** 62^7，上界（不含）。 */
    public static final long MAX_ID_EXCLUSIVE = 3_521_614_606_208L;

    /** 可用码空间 = 62^7 - 62^6。 */
    public static final long DOMAIN = MAX_ID_EXCLUSIVE - MIN_ID;

    /** 短码长度。 */
    public static final int CODE_LENGTH = 7;

    private final FeistelPermutation permutation;

    public ShortCodeCodec(long key) {
        this.permutation = new FeistelPermutation(key);
    }

    /**
     * 号段号 -> 7 位短码。
     *
     * @param segmentId 号段发出来的原始号，取值 [62^6, 62^7)
     */
    public String encode(long segmentId) {
        if (segmentId < MIN_ID || segmentId >= MAX_ID_EXCLUSIVE) {
            throw new IllegalArgumentException(
                    "segmentId out of range [" + MIN_ID + ", " + MAX_ID_EXCLUSIVE + "): " + segmentId);
        }
        long x = segmentId - MIN_ID;
        long y = permutation.encrypt(x);
        // 循环行走：只在非法区间重试，期望 1.27 次
        int guard = 0;
        while (y >= DOMAIN) {
            y = permutation.encrypt(y);
            if (++guard > 64) {
                // 理论上不可达；留着是为了防止密钥/参数错误导致死循环
                throw new IllegalStateException("cycle-walking did not converge for segmentId=" + segmentId);
            }
        }
        return Base62.encode(y + MIN_ID);
    }

    /**
     * 7 位短码 -> 号段号。
     *
     * <p>逆循环行走：从终点沿 Feistel 逆变换往回走，第一个落回合法区间的值就是原始号。</p>
     */
    public long decode(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            throw new IllegalArgumentException("short code must be " + CODE_LENGTH + " chars: " + code);
        }
        long value = Base62.decode(code);
        if (value < MIN_ID || value >= MAX_ID_EXCLUSIVE) {
            throw new IllegalArgumentException("decoded value out of range: " + value);
        }
        long y = value - MIN_ID;
        int guard = 0;
        while (true) {
            long prev = permutation.decrypt(y);
            if (prev < DOMAIN) {
                return prev + MIN_ID;
            }
            y = prev;
            if (++guard > 64) {
                throw new IllegalStateException("reverse cycle-walking did not converge for code=" + code);
            }
        }
    }

    /** 只校验格式，不抛异常；用于 redirect 的第一道拦截（非法格式直接 404，不查缓存）。 */
    public static boolean isValidFormat(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < CODE_LENGTH; i++) {
            char c = code.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
