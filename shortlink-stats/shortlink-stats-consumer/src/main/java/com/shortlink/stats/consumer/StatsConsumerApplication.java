package com.shortlink.stats.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统计消费服务。
 *
 * <p>核心难点不在“消费”，而在三件事：</p>
 * <ol>
 *   <li><b>幂等</b>：Kafka 至少一次投递，重复消息会让 PV 虚高，必须按 eventId 去重；</li>
 *   <li><b>批量</b>：一条一条写 ClickHouse 会把 CH 打爆，必须攒批（500 条 / 1s）；</li>
 *   <li><b>背压</b>：消费不过来时要能感知（lag 指标），而不是把内存撑爆。</li>
 * </ol>
 *
 * <p>骨架阶段只搭好应用与配置；消费逻辑在 V3 实现。</p>
 */
@SpringBootApplication
public class StatsConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatsConsumerApplication.class, args);
    }
}
