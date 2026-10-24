package com.shortlink.job;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 定时任务服务。
 *
 * <p>为什么独立部署而不是塞进 admin：</p>
 * <ul>
 *   <li>任务会跑全表扫描（过期短链清理、对账），放在 admin 里会和管理接口抢连接池与 CPU；</li>
 *   <li>任务需要“单实例执行”语义，独立部署后配合分布式锁即可，不必给 admin 也加锁逻辑；</li>
 *   <li>出问题时可以单独停掉任务而不影响写侧可用性。</li>
 * </ul>
 *
 * <p>调度器选型见设计文档：XXL-JOB（有控制台、失败重试、分片广播）。骨架阶段先留空。</p>
 */
@SpringBootApplication
public class JobApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobApplication.class, args);
    }
}
