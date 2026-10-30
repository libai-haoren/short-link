package com.shortlink.admin.support;

import com.shortlink.common.core.constant.HeaderConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * 当前操作人（以及来源 IP）。
 *
 * <p>身份由网关注入到 {@code X-User-Id} / {@code X-Username}，admin 只负责读取——
 * 不在 admin 里再解一遍 JWT，避免出现"网关校验了一套、admin 校验了另一套"的不一致。</p>
 *
 * <p><b>为什么用静态方法而不是注入 Bean</b>：这是"从当前线程的请求上下文取值"的纯读操作，
 * 和 MDC/traceId 同类。做成 Bean 会让 {@code LinkServiceImpl} 的构造参数涨到 11 个，
 * 而它本身没有任何状态需要复用。</p>
 *
 * <p><b>安全前提</b>：这几个头必须由网关覆盖写入。当前 admin 的 8082 端口是直接暴露的，
 * 绕过网关就能伪造 {@code X-User-Id}——生产部署必须让 admin 只对网关开放。</p>
 *
 * <p>没有请求上下文（比如定时任务里调用）时返回"匿名"，不抛异常：
 * 审计少一个操作人，比让一次正常的写操作失败严重性低得多。</p>
 */
public final class CurrentUser {

    /** 匿名/系统操作的 ID，与表里的默认值一致。 */
    public static final long ANONYMOUS_ID = 0L;

    private CurrentUser() {
    }

    /**
     * 当前登录用户 ID。
     *
     * @return 为空表示没经过网关（直连 admin）或网关未注入
     */
    public static Optional<Long> id() {
        String raw = header(HeaderConstants.USER_ID);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(raw.trim()));
        } catch (NumberFormatException ex) {
            // 头被改坏了：当作匿名，但绝不能因此让业务失败
            return Optional.empty();
        }
    }

    public static long idOrAnonymous() {
        return id().orElse(ANONYMOUS_ID);
    }

    public static String username() {
        String raw = header("X-Username");
        return raw == null ? "" : raw.trim();
    }

    /**
     * 客户端 IP。
     *
     * <p>优先用网关注入的 {@code X-Client-Ip}（覆盖写入、可信），
     * 直连 admin 时退化成 remote address。</p>
     */
    public static String clientIp() {
        String fromGateway = header(HeaderConstants.CLIENT_IP);
        if (fromGateway != null && !fromGateway.isBlank()) {
            return fromGateway.trim();
        }
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "";
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote;
    }

    private static String header(String name) {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getHeader(name);
    }

    private static HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }
}
