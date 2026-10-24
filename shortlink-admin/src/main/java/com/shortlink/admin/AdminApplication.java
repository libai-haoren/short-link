package com.shortlink.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 短链管理服务（写侧）。
 *
 * <p>写侧 QPS 很低（个位数到百级），所以设计取向是“清晰、可校验、可审计”，
 * 而不是极致性能：同步事务、参数校验、操作日志一个都不能少。</p>
 *
 * <p>与 redirect 的耦合方式只有两种：写 Redis（数据面）和发 MQ（控制面）。
 * admin 挂了不影响跳转，这是本架构最重要的隔离目标。</p>
 */
@SpringBootApplication
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
