package com.shortlink.common.web;

import com.shortlink.common.core.constant.HeaderConstants;
import com.shortlink.common.core.trace.TraceContext;
import com.shortlink.common.core.util.Ulid;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * TraceId 过滤器（Servlet 栈）。
 *
 * <p>为什么用 filter 而不是拦截器：filter 在 DispatcherServlet 之前执行，
 * 能覆盖 404、参数解析失败、甚至静态资源，保证“任何请求都有 traceId”。</p>
 *
 * <p>务必在 finally 里清理 ThreadLocal 与 MDC：Tomcat 线程复用，
 * 不清理会把上一个请求的 traceId 带到下一个请求（典型的串号 bug）。</p>
 */
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(HeaderConstants.TRACE_ID);
        if (!StringUtils.hasText(traceId)) {
            traceId = Ulid.next();
        }
        TraceContext.set(traceId);
        MDC.put("traceId", traceId);
        response.setHeader(HeaderConstants.TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
            MDC.remove("traceId");
        }
    }
}
