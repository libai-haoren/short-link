package com.shortlink.stats.consumer.config;

import com.shortlink.stats.consumer.service.ClickHouseWriter;
import com.shortlink.stats.consumer.service.ClickProcessor;
import com.shortlink.stats.consumer.service.DedupGuard;
import com.shortlink.stats.consumer.service.RealtimeCounter;
import com.shortlink.stats.consumer.support.BatchClickHouseSink;
import com.shortlink.stats.consumer.support.JdbcClickHouseWriter;
import com.shortlink.stats.consumer.support.RedisDedupGuard;
import com.shortlink.stats.consumer.support.RedisRealtimeCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * 消费端装配。
 *
 * <p>所有策略参数都走配置：批量大小、刷新间隔、缓冲区大小。
 * 这几个值直接决定"吞吐"与"内存"的平衡，必须能不改代码就调。</p>
 */
@Configuration
public class StatsConsumerConfig {

    @Bean
    public DedupGuard dedupGuard(StringRedisTemplate redisTemplate) {
        return new RedisDedupGuard(redisTemplate);
    }

    @Bean
    public RealtimeCounter realtimeCounter(StringRedisTemplate redisTemplate) {
        return new RedisRealtimeCounter(redisTemplate);
    }

    /**
     * ClickHouse 写入器。
     *
     * <p>连接池由 {@code spring.datasource} 提供（指向 ClickHouse 的 JDBC URL），
     * 这里只依赖标准的 {@link DataSource}，编译期不需要 ClickHouse 驱动。</p>
     */
    @Bean
    public ClickHouseWriter clickHouseWriter(DataSource dataSource) {
        return new JdbcClickHouseWriter(dataSource);
    }

    @Bean
    public BatchClickHouseSink clickSink(ClickHouseWriter writer,
                                         MeterRegistry meterRegistry,
                                         @Value("${shortlink.stats.buffer-size:10000}") int bufferSize,
                                         @Value("${shortlink.stats.batch-size:500}") int batchSize,
                                         @Value("${shortlink.stats.flush-interval:1s}") Duration flushInterval) {
        return new BatchClickHouseSink(writer, bufferSize, batchSize, flushInterval, meterRegistry);
    }

    @Bean
    public ClickProcessor clickProcessor(DedupGuard dedupGuard,
                                         BatchClickHouseSink clickSink,
                                         RealtimeCounter realtimeCounter,
                                         MeterRegistry meterRegistry) {
        return new ClickProcessor(dedupGuard, clickSink, realtimeCounter, meterRegistry);
    }
}
