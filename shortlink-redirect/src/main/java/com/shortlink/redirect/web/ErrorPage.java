package com.shortlink.redirect.web;

import com.shortlink.redirect.service.ResolveResult.Outcome;

import java.nio.charset.StandardCharsets;

/**
 * 错误页渲染。
 *
 * <p>面向浏览器的服务不能返回 JSON 错误体：用户点开一个失效短链，
 * 看到 {@code {"timestamp":...,"status":404}} 只会觉得这个网站坏了。</p>
 *
 * <p>两条安全约束：</p>
 * <ol>
 *   <li><b>不回显 shortCode</b>。把用户输入拼进 HTML 是标准的反射型 XSS 入口，
 *       而这个信息对用户没有任何价值。</li>
 *   <li>不引用任何外部资源（CSS/字体/图片），页面必须是单文件、可离线渲染。</li>
 * </ol>
 */
public final class ErrorPage {

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="robots" content="noindex,nofollow">
              <title>%s</title>
              <style>
                body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
                     background:#f7f8fa;color:#1f2329;
                     font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}
                .card{max-width:420px;padding:40px 32px;text-align:center;background:#fff;border-radius:12px;
                      box-shadow:0 2px 12px rgba(0,0,0,.06)}
                .code{font-size:56px;font-weight:600;letter-spacing:2px;color:#c9cdd4;line-height:1}
                h1{margin:16px 0 8px;font-size:20px;font-weight:600}
                p{margin:0;color:#646a73}
              </style>
            </head>
            <body>
              <div class="card">
                <div class="code">%d</div>
                <h1>%s</h1>
                <p>%s</p>
              </div>
            </body>
            </html>
            """;

    private ErrorPage() {
    }

    /**
     * 渲染错误页。
     *
     * @param outcome 非 FOUND 的任意结果
     * @return UTF-8 编码的 HTML 字节
     */
    public static byte[] render(Outcome outcome) {
        String title;
        String message;
        switch (outcome) {
            case DISABLED -> {
                title = "链接已停用";
                message = "该短链接已被停用，请联系分享者。";
            }
            case EXPIRED -> {
                title = "链接已过期";
                message = "该短链接已超过有效期。";
            }
            case QUOTA_EXCEEDED -> {
                title = "访问次数已达上限";
                message = "该短链接的访问次数已用完。";
            }
            case UNAVAILABLE -> {
                title = "服务暂时不可用";
                message = "请稍后重试。";
            }
            case INVALID, NOT_FOUND -> {
                title = "链接不存在";
                message = "你访问的短链接不存在或已被删除。";
            }
            default -> {
                title = "链接不存在";
                message = "你访问的短链接不存在或已被删除。";
            }
        }
        return String.format(TEMPLATE, title, statusOf(outcome), title, message)
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 403：IP 在黑名单里。
     *
     * <p>与"链接已停用"共用 403 但文案不同：这里是访问者被限制，不是链接失效，
     * 混在一起会让被误封的用户以为是链接坏了。</p>
     */
    public static byte[] renderForbidden() {
        return render("访问受限", 403, "当前网络环境的访问请求被拒绝。");
    }

    /** 429：触发本地限流。 */
    public static byte[] renderTooManyRequests() {
        return render("请求过于频繁", 429, "请稍后再试。");
    }

    private static byte[] render(String title, int status, String message) {
        return String.format(TEMPLATE, title, status, title, message)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** HTTP 状态码映射，渲染与响应头共用同一张表，避免两处不一致。 */
    public static int statusOf(Outcome outcome) {
        return switch (outcome) {
            case DISABLED, QUOTA_EXCEEDED -> 403;
            case EXPIRED -> 410;
            case UNAVAILABLE -> 503;
            case INVALID, NOT_FOUND -> 404;
            case FOUND -> 302;
        };
    }
}
