package com.shortlink.common.core.exception;

import com.shortlink.common.core.result.ErrorCode;

import java.io.Serial;

/**
 * 业务异常。
 *
 * <p>只用于“可预期”的失败（参数错、资源不存在、限流等），不要用它包装系统异常。
 * 全局异常处理器会把它翻译成 {@code Result} + 对应 HTTP 状态。</p>
 */
public class BizException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getCode() {
        return errorCode.code();
    }

    public int getHttpStatus() {
        return errorCode.httpStatus();
    }

    /** 轻量工厂，避免在业务代码里反复 new。 */
    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }
}
