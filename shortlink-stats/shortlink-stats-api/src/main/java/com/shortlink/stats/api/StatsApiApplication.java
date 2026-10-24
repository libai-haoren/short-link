package com.shortlink.stats.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统计查询服务。
 *
 * <p>与 stats-consumer 分开部署的原因：查询是“用户在页面上点一下”的低频高延迟操作，
 * 消费是“持续不断”的吞吐型任务。混在一起会出现两种情况：</p>
 * <ul>
 *   <li>大报表查询把消费线程的 CPU 抢走，导致 Kafka 堆积；</li>
 *   <li>消费积压时查询接口 RT 抖动，用户以为系统坏了。</li>
 * </ul>
 * <p>拆开后各自的扩缩容策略也完全不同（消费按 lag 扩容，查询按 QPS 扩容）。</p>
 */
@SpringBootApplication
public class StatsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatsApiApplication.class, args);
    }
}
