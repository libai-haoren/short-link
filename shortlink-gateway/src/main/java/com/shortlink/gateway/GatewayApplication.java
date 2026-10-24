package com.shortlink.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网关服务。
 *
 * <p>职责边界（见设计文档 2.2）：</p>
 * <ul>
 *   <li>只服务 admin / stats 的 API 流量；</li>
 *   <li>redirect 走独立域名直连 LB，<b>不经过本网关</b>——否则网关要扛全量跳转 QPS，
 *       扩容成本和故障面都会翻倍。</li>
 * </ul>
 *
 * <p>因此网关上的限流/黑名单只保护“管理面”，跳转面的黑名单靠 MQ 广播到 redirect 本地。</p>
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
