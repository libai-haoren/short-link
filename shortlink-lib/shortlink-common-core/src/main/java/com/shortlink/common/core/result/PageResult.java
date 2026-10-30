package com.shortlink.common.core.result;

import java.util.List;

/**
 * 游标分页结果。
 *
 * <p>为什么不用 {@code page/size} 的 offset 分页：{@code LIMIT 100000, 20} 要先扫过 10 万行才丢弃，
 * 深分页时 RT 随页码线性上升。游标分页把 {@code WHERE id < cursor} 交给主键索引，
 * 代价与翻到第几页无关。</p>
 *
 * <p>游标对客户端是<b>不透明</b>的：现在它恰好是自增主键，将来换成分片键或复合游标时，
 * 客户端代码不需要改。所以这里用 String 而不是 Long。</p>
 *
 * @param items      当前页数据
 * @param nextCursor 下一页游标；{@code hasMore=false} 时为 null
 * @param hasMore    是否还有下一页
 */
public record PageResult<T>(List<T> items, String nextCursor, boolean hasMore) {

    public static <T> PageResult<T> of(List<T> items, String nextCursor, boolean hasMore) {
        return new PageResult<>(items, nextCursor, hasMore);
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), null, false);
    }
}
