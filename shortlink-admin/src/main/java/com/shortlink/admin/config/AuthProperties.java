package com.shortlink.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 管理端登录配置（V4 占位实现）。
 *
 * <p>只支持一个内置账号，口令存的是 <b>SHA-256 十六进制</b>而不是明文——
 * 配置文件经常被提交进仓库或贴进工单，明文口令是实打实的泄露。</p>
 *
 * <p><b>这不是生产方案</b>：真正的实现应该是用户表 + BCrypt/Argon2 + 加盐 + 登录失败锁定。
 * 这里保留 SHA-256 是因为它不引入额外依赖，同时至少避免了明文存储。V5 接用户体系时替换。</p>
 *
 * @param username 账号
 * @param passwordSha256 口令的 SHA-256 十六进制（小写）
 * @param userId 该账号对应的用户 ID，写进 JWT
 */
@ConfigurationProperties(prefix = "shortlink.admin.auth")
public record AuthProperties(
        @DefaultValue("admin") String username,
        // sha256("admin123")
        @DefaultValue("240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9")
        String passwordSha256,
        @DefaultValue("1") long userId) {
}
