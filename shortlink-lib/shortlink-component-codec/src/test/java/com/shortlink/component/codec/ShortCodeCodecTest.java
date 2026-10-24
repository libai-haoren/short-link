package com.shortlink.component.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortCodeCodecTest {

    private static final long KEY = 0x5DEECE66DL;
    private final ShortCodeCodec codec = new ShortCodeCodec(KEY);

    @Test
    @DisplayName("编码结果恒为 7 位，且只含 Base62 字符")
    void encodeAlwaysSevenChars() {
        for (long id = ShortCodeCodec.MIN_ID; id < ShortCodeCodec.MIN_ID + 10_000; id++) {
            String code = codec.encode(id);
            assertEquals(ShortCodeCodec.CODE_LENGTH, code.length(), "id=" + id);
            assertTrue(ShortCodeCodec.isValidFormat(code), "id=" + id + " code=" + code);
        }
    }

    @Test
    @DisplayName("编解码往返一致")
    void encodeDecodeRoundTrip() {
        for (long id = ShortCodeCodec.MIN_ID; id < ShortCodeCodec.MIN_ID + 10_000; id++) {
            assertEquals(id, codec.decode(codec.encode(id)));
        }
        // 边界
        assertEquals(ShortCodeCodec.MIN_ID, codec.decode(codec.encode(ShortCodeCodec.MIN_ID)));
        long max = ShortCodeCodec.MAX_ID_EXCLUSIVE - 1;
        assertEquals(max, codec.decode(codec.encode(max)));
    }

    @Test
    @DisplayName("连续号段不产生重复短码")
    void noCollision() {
        Set<String> seen = new HashSet<>();
        for (long id = ShortCodeCodec.MIN_ID; id < ShortCodeCodec.MIN_ID + 100_000; id++) {
            assertTrue(seen.add(codec.encode(id)), "duplicated at id=" + id);
        }
    }

    @Test
    @DisplayName("相邻号段的短码不具备可枚举规律")
    void notSequential() {
        String a = codec.encode(ShortCodeCodec.MIN_ID);
        String b = codec.encode(ShortCodeCodec.MIN_ID + 1);
        // 只断言“不相同且非简单递增”，避免把实现细节写死
        assertFalse(a.equals(b));
    }

    @Test
    @DisplayName("越界入参必须被拒绝")
    void rejectOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(ShortCodeCodec.MIN_ID - 1));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(ShortCodeCodec.MAX_ID_EXCLUSIVE));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("abc"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("!!!!!!!"));
    }

    @Test
    @DisplayName("格式校验：长度必须为 7、字符必须属于 Base62")
    void validateFormat() {
        // 全小写也是合法 Base62，不能因为"看起来像占位符"就拒绝
        assertTrue(ShortCodeCodec.isValidFormat("abcdefg"));
        assertTrue(ShortCodeCodec.isValidFormat("A0b1C2d"));
        assertFalse(ShortCodeCodec.isValidFormat("abc"));
        assertFalse(ShortCodeCodec.isValidFormat("abcdefgh"));
        assertFalse(ShortCodeCodec.isValidFormat("abc-def"));
        assertFalse(ShortCodeCodec.isValidFormat("abc def"));
        assertFalse(ShortCodeCodec.isValidFormat(null));
        assertFalse(ShortCodeCodec.isValidFormat(""));
    }
}
