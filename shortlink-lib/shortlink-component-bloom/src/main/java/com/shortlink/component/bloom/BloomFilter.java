package com.shortlink.component.bloom;

/**
 * 布隆过滤器抽象。
 *
 * <p>语义：{@link #mightContain} 返回 false 则<b>一定不存在</b>；返回 true 则“可能存在”。
 * 这个“单向确定性”是它能挡穿透的原因——误判只会放过多余请求，不会误杀真实短链。</p>
 */
public interface BloomFilter {

    /** 加入元素。 */
    void add(String value);

    /** 批量加入，减少 Redis 往返。 */
    void addAll(Iterable<String> values);

    /** 可能存在返回 true；一定不存在返回 false。 */
    boolean mightContain(String value);

    /** 理论容量，用于监控是否该扩容。 */
    long capacity();
}
