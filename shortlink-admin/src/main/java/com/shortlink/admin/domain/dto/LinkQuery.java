package com.shortlink.admin.domain.dto;

/**
 * 列表查询条件。
 *
 * @param groupId 分组过滤，null 表示全部
 * @param status  状态过滤，null 表示全部
 * @param keyword 关键字：匹配标题（模糊）、短码、gid（精确）
 * @param cursor  游标，即上一页最后一条的 id；首页传 null
 * @param size    每页条数，默认 20，上限 100
 */
public record LinkQuery(
        Long groupId,
        Integer status,
        String keyword,
        String cursor,
        int size
) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /**
     * 兜住非法 size，而不是抛异常：
     * 分页大小属于"客户端随便传"的参数，夹逼比报错体验好，也不会让人刷出 100 万条。
     */
    public LinkQuery {
        if (size <= 0) {
            size = DEFAULT_SIZE;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
        if (groupId != null && groupId < 0) {
            throw new IllegalArgumentException("groupId 不能为负数");
        }
        if (status != null && status != 0 && status != 1) {
            throw new IllegalArgumentException("status 只能是 0 或 1");
        }
    }
}
