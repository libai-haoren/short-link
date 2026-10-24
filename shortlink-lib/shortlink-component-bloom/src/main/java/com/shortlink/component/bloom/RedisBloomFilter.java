package com.shortlink.component.bloom;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 基于 Redis Bitmap 的布隆过滤器。
 *
 * <p>参数推导（设计文档 5.3）：误判率 p=0.001 时，</p>
 * <pre>
 *   m = -n·ln(p) / (ln2)^2 ≈ 14.4 · n   (bit)
 *   k = (m/n)·ln2          ≈ 10
 * </pre>
 * <p>即 1000 万短码约需 1.44 亿 bit ≈ 18MB，用 Redis Bitmap 存完全可接受。</p>
 *
 * <p><b>已知限制（必须知道，否则线上出事）</b>：</p>
 * <ol>
 *   <li>不支持删除：短链被封禁时不能清位，否则会影响其他短码的判定。
 *       解决方式是不删，让空值缓存与 status 字段兜底。</li>
 *   <li>扩容需要重建：位图大小固定，扩容要新建一个更大的 key 双写，切换后再删旧 key。</li>
 *   <li>Redis 挂了则降级：{@link #mightContain} 异常时应向上抛出并让调用方跳过该层，
 *       绝不能 catch 后返回 false（那会把所有真实短链也挡掉）。</li>
 * </ol>
 */
public class RedisBloomFilter implements BloomFilter {

    /** 每个元素的位数，由 p=0.001 推得。 */
    private static final long BITS_PER_ELEMENT = 14L;

    /** 哈希函数个数，由 m/n 推得。 */
    private static final int HASH_COUNT = 10;

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final StringRedisTemplate redisTemplate;
    private final String key;
    private final long bitSize;
    private final long capacity;

    public RedisBloomFilter(StringRedisTemplate redisTemplate, String key, long expectedElements) {
        if (expectedElements <= 0) {
            throw new IllegalArgumentException("expectedElements must be > 0");
        }
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.key = Objects.requireNonNull(key, "key");
        this.capacity = expectedElements;
        // 向上取整到 64 的倍数，避免最后一个 bit 越界
        this.bitSize = ((expectedElements * BITS_PER_ELEMENT + 63) / 64) * 64;
    }

    @Override
    public void add(String value) {
        long[] offsets = offsets(value);
        byte[] rawKey = rawKey();
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            for (long offset : offsets) {
                connection.stringCommands().setBit(rawKey, offset, true);
            }
            return null;
        });
    }

    @Override
    public void addAll(Iterable<String> values) {
        byte[] rawKey = rawKey();
        redisTemplate.executePipelined((RedisCallback<Void>) connection -> {
            for (String value : values) {
                for (long offset : offsets(value)) {
                    connection.stringCommands().setBit(rawKey, offset, true);
                }
            }
            return null;
        });
    }

    @Override
    public boolean mightContain(String value) {
        long[] offsets = offsets(value);
        byte[] rawKey = rawKey();
        Boolean result = redisTemplate.execute((RedisCallback<Boolean>) connection -> {
            for (long offset : offsets) {
                if (!Boolean.TRUE.equals(getBit(connection, rawKey, offset))) {
                    return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(result);
    }

    @Override
    public long capacity() {
        return capacity;
    }

    private static Boolean getBit(RedisConnection connection, byte[] key, long offset) {
        return connection.stringCommands().getBit(key, offset);
    }

    /**
     * 双重哈希：h_i = (h1 + i·h2) mod m。
     *
     * <p>只算两次哈希就能得到 k 个独立位置，比调 k 次哈希函数快一个量级，
     * 且误判率与独立哈希几乎无差别（Kirsch-Mitzenmacher）。</p>
     */
    private long[] offsets(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        long h1 = fnv1a(bytes);
        long h2 = mix(h1 ^ 0x9E3779B97F4A7C15L) | 1L; // 保证 h2 为奇数，避免步长为 0
        long[] result = new long[HASH_COUNT];
        for (int i = 0; i < HASH_COUNT; i++) {
            long combined = h1 + i * h2;
            result[i] = Math.floorMod(combined, bitSize);
        }
        return result;
    }

    private static long fnv1a(byte[] bytes) {
        long hash = FNV_OFFSET_BASIS;
        for (byte b : bytes) {
            hash ^= (b & 0xFF);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private byte[] rawKey() {
        return key.getBytes(StandardCharsets.UTF_8);
    }
}
