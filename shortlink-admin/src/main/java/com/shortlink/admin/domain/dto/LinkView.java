package com.shortlink.admin.domain.dto;

import java.time.LocalDateTime;

/**
 * 短链视图对象（创建与详情共用）。
 *
 * @param gid        业务 ID，后续改/删/查详情都用它，不用短码
 * @param shortCode  短码
 * @param shortUrl   完整短链，前端直接展示
 * @param longUrl    原始长链
 * @param status     1 启用 0 禁用
 * @param expireTime 过期时间，null 表示永久
 * @param createTime 创建时间
 */
public record LinkView(
        String gid,
        String shortCode,
        String shortUrl,
        String longUrl,
        Integer status,
        LocalDateTime expireTime,
        LocalDateTime createTime
) {
}
