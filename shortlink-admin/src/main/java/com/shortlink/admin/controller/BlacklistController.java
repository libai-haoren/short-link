package com.shortlink.admin.controller;

import com.shortlink.admin.service.BlacklistService;
import com.shortlink.common.core.exception.BizException;
import com.shortlink.common.core.result.ErrorCode;
import com.shortlink.common.core.result.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IP 黑名单管理接口。
 *
 * <p>写入 Redis 后，gateway 与 redirect 会在刷新间隔（默认 2s）内自动生效，
 * 不需要重启任何服务。</p>
 */
@RestController
@RequestMapping("/api/v1/blacklist")
public class BlacklistController {

    private final BlacklistService blacklistService;

    public BlacklistController(BlacklistService blacklistService) {
        this.blacklistService = blacklistService;
    }

    /**
     * 拉黑一个 IP。
     *
     * <pre>
     * curl -X POST http://127.0.0.1:8080/api/v1/blacklist/ip \
     *   -H 'Content-Type: application/json' \
     *   -d '{"ip":"1.2.3.4"}'
     * </pre>
     */
    @PostMapping("/ip")
    public Result<Map<String, Object>> add(@RequestBody IpRequest request) {
        String ip = normalize(request == null ? null : request.ip());
        boolean added = blacklistService.add(ip);
        Map<String, Object> data = new LinkedHashMap<>(2);
        data.put("ip", ip);
        data.put("added", added);
        return Result.ok(data);
    }

    /**
     * 解除拉黑。
     *
     * <p>用路径参数而不是 DELETE + body：不少 HTTP 客户端（含 PowerShell 5.1 的
     * {@code Invoke-RestMethod}）在 DELETE 上不带 body，会导致参数永远收不到。</p>
     */
    @DeleteMapping("/ip/{ip}")
    public Result<Map<String, Object>> remove(@PathVariable String ip) {
        String normalized = normalize(ip);
        boolean removed = blacklistService.remove(normalized);
        Map<String, Object> data = new LinkedHashMap<>(2);
        data.put("ip", normalized);
        data.put("removed", removed);
        return Result.ok(data);
    }

    @GetMapping("/ip")
    public Result<List<String>> list() {
        return Result.ok(blacklistService.list());
    }

    private static String normalize(String ip) {
        if (ip == null || ip.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "ip 不能为空");
        }
        return ip.trim();
    }

    /** @param ip IPv4 / IPv6 字符串 */
    public record IpRequest(String ip) {
    }
}
