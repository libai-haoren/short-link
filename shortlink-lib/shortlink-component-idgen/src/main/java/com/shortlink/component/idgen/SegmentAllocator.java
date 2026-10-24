package com.shortlink.component.idgen;

/**
 * 号段分配器：负责从持久层“批发”一段连续 ID。
 *
 * <p>抽象出接口是为了让 {@link SegmentIdGenerator} 保持零外部依赖，
 * 单测里可以用内存实现，不需要数据库。</p>
 */
public interface SegmentAllocator {

    /**
     * 申请一个号段。
     *
     * @param bizTag 业务标识，如 short_code
     * @param step   号段长度
     * @return 左闭右开区间 [startInclusive, endExclusive)
     */
    Segment allocate(String bizTag, int step);

    /**
     * 号段区间。
     *
     * @param startInclusive 起始号（含）
     * @param endExclusive   结束号（不含）
     */
    record Segment(long startInclusive, long endExclusive) {

        public long size() {
            return endExclusive - startInclusive;
        }
    }
}
