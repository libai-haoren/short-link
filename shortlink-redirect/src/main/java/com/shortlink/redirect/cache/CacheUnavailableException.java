package com.shortlink.redirect.cache;

import java.io.Serial;

/**
 * 缓存不可用：Redis 连接失败、超时、命令执行异常。
 *
 * <p>与"未命中"严格区分：未命中是正常业务结果（404），不可用是依赖故障（503）。
 * 把两者混为一谈，等于故障时向用户撒谎说链接不存在。</p>
 */
public class CacheUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
