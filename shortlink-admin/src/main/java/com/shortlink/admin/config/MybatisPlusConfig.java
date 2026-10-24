package com.shortlink.admin.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>Mapper 用 {@code @MapperScan} 统一扫描，不在每个接口上加 {@code @Mapper}：
 * 后者容易漏，漏了就是启动后注入失败，报错信息还很难懂。</p>
 */
@Configuration
@MapperScan("com.shortlink.admin.mapper")
public class MybatisPlusConfig {
}
