package com.shortlink.admin.domain.dto;

import java.util.List;

/**
 * 批量创建请求。
 *
 * <p>用 JSON 数组而不是 CSV 上传：调用方（脚本/内部系统）拼 JSON 更自然，
 * 也省掉 multipart 解析与临时文件存储。真要做运营用的 CSV 导入，
 * 再叠一层"解析 CSV → 转成这个 DTO"即可。</p>
 *
 * @param items 待创建的短链，单次上限见服务端校验（默认 500）
 */
public record BatchCreateRequest(List<Item> items) {

    /**
     * @param longUrl 必填
     * @param title   选填
     */
    public record Item(String longUrl, String title) {
    }
}
