package com.shortlink.admin.domain.dto;

import java.util.List;

/**
 * 批量创建结果。
 *
 * <p>返回<b>逐条失败原因</b>而不是只给个数：批量导入最怕"200 条里挂了 3 条但不知道是哪 3 条"。</p>
 *
 * @param total    提交总数
 * @param success  成功数
 * @param failed   失败数
 * @param failures 失败明细（最多与 total 等长）
 */
public record BatchCreateResult(int total, int success, int failed, List<Failure> failures) {

    /**
     * @param index   在请求数组里的下标，方便调用方定位原始数据
     * @param longUrl 原始长链
     * @param reason  失败原因
     */
    public record Failure(int index, String longUrl, String reason) {
    }
}
