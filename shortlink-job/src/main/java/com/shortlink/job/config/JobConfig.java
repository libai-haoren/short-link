package com.shortlink.job.config;

import com.shortlink.job.lock.JobLock;
import com.shortlink.job.service.AuditArchiveService;
import com.shortlink.job.service.CacheEvictor;
import com.shortlink.job.service.JdbcLinkStore;
import com.shortlink.job.service.LinkCleanupService;
import com.shortlink.job.service.LinkStore;
import com.shortlink.job.service.RedisCacheEvictor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/**
 * 任务装配。
 */
@Configuration
@EnableConfigurationProperties(JobProperties.class)
public class JobConfig {

    @Bean
    public LinkStore linkStore(JdbcTemplate jdbcTemplate) {
        return new JdbcLinkStore(jdbcTemplate);
    }

    @Bean
    public CacheEvictor cacheEvictor(StringRedisTemplate redisTemplate) {
        return new RedisCacheEvictor(redisTemplate);
    }

    @Bean
    public JobLock jobLock(StringRedisTemplate redisTemplate, JobProperties properties) {
        return new JobLock(redisTemplate, properties.lockTtl());
    }

    @Bean
    public LinkCleanupService linkCleanupService(LinkStore linkStore,
                                                 CacheEvictor cacheEvictor,
                                                 JobProperties properties,
                                                 MeterRegistry meterRegistry) {
        return new LinkCleanupService(linkStore, cacheEvictor,
                Duration.ofDays(properties.retentionDays()),
                properties.batchSize(), properties.maxBatches(), meterRegistry);
    }

    @Bean
    public AuditArchiveService auditArchiveService(JdbcTemplate jdbcTemplate,
                                                   JobProperties properties,
                                                   MeterRegistry meterRegistry) {
        return new AuditArchiveService(jdbcTemplate,
                properties.auditRetentionDays(),
                properties.batchSize(), properties.maxBatches(), meterRegistry);
    }
}
