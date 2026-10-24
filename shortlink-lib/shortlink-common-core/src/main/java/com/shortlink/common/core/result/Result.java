package com.shortlink.common.core.result;

import java.io.Serializable;
import java.util.Objects;

/**
 * 统一响应体。
 *
 * <p>对外 HTTP 接口一律返回该结构；redirect 的跳转接口例外（它返回 302 + Location，无 body）。</p>
 *
 * @param <T> 业务数据类型
 */
public final class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final String msg;
    private final T data;
    private final String traceId;

    private Result(int code, String msg, T data, String traceId) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), data, null);
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode");
        return new Result<>(errorCode.code(), errorCode.message(), null, null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String msg) {
        Objects.requireNonNull(errorCode, "errorCode");
        return new Result<>(errorCode.code(), msg == null ? errorCode.message() : msg, null, null);
    }

    public Result<T> withTraceId(String traceId) {
        return new Result<>(code, msg, data, traceId);
    }

    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.code();
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }
}
