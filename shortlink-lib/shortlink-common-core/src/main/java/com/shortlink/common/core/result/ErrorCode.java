package com.shortlink.common.core.result;

/**
 * 全局错误码。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>code 为 5 位：前 3 位对应 HTTP 状态，后 2 位为业务序号；0 表示成功。</li>
 *   <li>新增错误码只允许追加，禁止复用已删除的值。</li>
 * </ul>
 */
public enum ErrorCode {

    SUCCESS(0, 200, "ok"),

    PARAM_INVALID(40001, 400, "参数校验失败"),
    URL_ILLEGAL(40002, 400, "长链非法"),
    UNAUTHORIZED(40101, 401, "未认证或凭证已过期"),
    FORBIDDEN(40301, 403, "无权限"),
    LINK_NOT_FOUND(40401, 404, "短链不存在"),
    LINK_EXISTS(40901, 409, "短链已存在"),
    IDEMPOTENT_IN_PROGRESS(40902, 409, "相同请求正在处理中，请稍后重试"),
    LINK_EXPIRED(41001, 410, "短链已过期"),
    LINK_DISABLED(41002, 410, "短链已禁用"),
    RATE_LIMITED(42901, 429, "请求过于频繁"),
    SYSTEM_ERROR(50001, 500, "系统异常"),
    SERVICE_UNAVAILABLE(50301, 503, "依赖服务不可用");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
