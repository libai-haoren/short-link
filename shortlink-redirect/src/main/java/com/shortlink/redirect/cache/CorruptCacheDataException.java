package com.shortlink.redirect.cache;

import java.io.Serial;

/**
 * 缓存数据损坏：Hash 存在但字段缺失或格式非法。
 *
 * <p>为什么不直接当作"不存在"返回 404：那会把数据错误伪装成正常业务结果，
 * 线上出问题时监控看不到任何异常。损坏必须表现为 503 + 告警。</p>
 */
public class CorruptCacheDataException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CorruptCacheDataException(String message) {
        super(message);
    }

    public CorruptCacheDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
