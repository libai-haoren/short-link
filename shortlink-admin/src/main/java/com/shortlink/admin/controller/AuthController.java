package com.shortlink.admin.controller;

import com.shortlink.admin.service.AuthService;
import com.shortlink.common.core.constant.HeaderConstants;
import com.shortlink.common.core.exception.BizException;
import com.shortlink.common.core.result.ErrorCode;
import com.shortlink.common.core.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口。
 *
 * <p>路径 {@code /api/v1/auth/**} 在网关的鉴权白名单里——否则就成"要 token 才能拿 token"了。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录换 token。
     *
     * <pre>
     * curl -X POST http://127.0.0.1:8080/api/v1/auth/login \
     *   -H 'Content-Type: application/json' \
     *   -d '{"username":"admin","password":"admin123"}'
     * </pre>
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.username(), request.password());
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("token", result.token());
        data.put("tokenType", "Bearer");
        data.put("expiresIn", result.expiresIn());
        return Result.ok(data);
    }

    /**
     * 回显当前调用者。
     *
     * <p>这个接口存在的唯一目的是<b>验证网关确实把身份透传下来了</b>：
     * 走网关访问时能拿到 userId，直连 admin 时因为缺少 {@code X-User-Id} 会返回 401。</p>
     */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(
            @RequestHeader(value = HeaderConstants.USER_ID, required = false) String userId,
            @RequestHeader(value = "X-Username", required = false) String username,
            @RequestHeader(value = HeaderConstants.CLIENT_IP, required = false) String clientIp) {
        if (userId == null || userId.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少网关透传的用户身份");
        }
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("userId", userId);
        data.put("username", username == null ? "" : username);
        // 回显客户端 IP：排障时确认"网关看到的来源"是不是预期的那个（尤其是 NAT / 代理之后）
        data.put("clientIp", clientIp == null ? "" : clientIp);
        return Result.ok(data);
    }

    /** @param password 开发环境默认 admin123 */
    public record LoginRequest(String username, String password) {
    }
}
