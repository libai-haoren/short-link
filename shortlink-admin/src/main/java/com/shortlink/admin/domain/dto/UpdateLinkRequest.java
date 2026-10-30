package com.shortlink.admin.domain.dto;

import java.time.LocalDateTime;

/**
 * 修改短链请求。
 *
 * <p><b>语义是全量替换，不是增量更新</b>：客户端先把详情拿全，改完整体提交。
 * 这样 {@code expireTime=null} 就明确表示"改成永久"，不需要再引入
 * "null 是不改还是清空"的歧义——那种歧义迟早会有人在线上踩到。</p>
 *
 * <p>注意：允许把 expireTime 设成过去的时间，等价于"立即过期"（跳转返回 410）。
 * 这是有意保留的能力，和"停用"（403）语义不同，运营排查时需要区分。</p>
 *
 * @param longUrl    必填，目标长链
 * @param title      备注标题，null 等价空串
 * @param expireTime 过期时间，null 表示永久
 * @param maxClick   最大点击次数，null 或 0 表示不限
 */
public record UpdateLinkRequest(
        String longUrl,
        String title,
        LocalDateTime expireTime,
        Long maxClick
) {
}
