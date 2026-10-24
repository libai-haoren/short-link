package com.shortlink.redirect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 跳转服务（读侧）。
 *
 * <p>本服务是整个项目的性能核心，设计约束全部围绕“单机尽可能高的 QPS”：</p>
 * <ul>
 *   <li>WebFlux + Netty：跳转是纯 IO 等待，事件循环模型避免线程栈与上下文切换开销；</li>
 *   <li>不连 MySQL：数据只来自 Caffeine（L1）+ Redis（L2），所以可以随流量无限水平扩副本；</li>
 *   <li>点击统计异步：本地队列 + 批量投递，队列满时丢统计而不是拖慢跳转。</li>
 * </ul>
 *
 * <p>代价要讲清楚：MySQL 里的修改必须通过 MQ 广播同步过来，存在秒级延迟。
 * 这是有意的取舍——短链改长链不是资金业务，秒级最终一致完全够用。</p>
 */
@SpringBootApplication
public class RedirectApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedirectApplication.class, args);
    }
}
