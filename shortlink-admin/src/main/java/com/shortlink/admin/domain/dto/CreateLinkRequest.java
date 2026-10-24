package com.shortlink.admin.domain.dto;

import java.time.LocalDateTime;

/**
 * 创建短链请求。
 *
 * <p>用 record 而不是带 setter 的类：请求对象天然不可变，且省掉一堆样板代码。
 * 校验放在 {@code UrlValidator} 里做而不是用 Bean Validation 注解，
 * 原因是 URL 的合法性（协议白名单、SSRF、长度）无法用注解表达。</p>
 *
 * @param longUrl    必填，长链，长度 ≤ 2048，仅允许 http/https
 * @param groupId    选填，默认 0（默认分组）
 * @param domainId   选填，默认 1（默认域名）
 * @param title      选填，备注标题
 * @param expireTime 选填，null 表示永久有效
 * @param maxClick   选填，0 或不传表示不限次数
 */
public record CreateLinkRequest(
        String longUrl,
        Long groupId,
        Long domainId,
        String title,
        LocalDateTime expireTime,
        Long maxClick
) {
}
