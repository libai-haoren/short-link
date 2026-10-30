package com.shortlink.admin.domain.dto;

import java.time.LocalDateTime;

/**
 * 短链视图对象（创建 / 详情 / 列表 / 修改共用）。
 *
 * <p>一个 VO 覆盖四个场景，而不是每个接口定义一个：字段少的时候拆开只会让前端多做一层适配。</p>
 *
 * @param gid        业务 ID，改/删/查详情都用它，不用短码（短码可被枚举）
 * @param shortCode  短码
 * @param shortUrl   完整短链，前端直接展示
 * @param longUrl    原始长链
 * @param title      备注标题，可能为空串
 * @param status     1 启用 0 停用
 * @param expireTime 过期时间，null 表示永久
 * @param maxClick   最大点击次数，0 表示不限
 * @param clickCount 累计点击（异步回写，可能滞后）
 * @param createTime 创建时间
 */
public record LinkView(
        String gid,
        String shortCode,
        String shortUrl,
        String longUrl,
        String title,
        Integer status,
        LocalDateTime expireTime,
        Long maxClick,
        Long clickCount,
        LocalDateTime createTime
) {
}
