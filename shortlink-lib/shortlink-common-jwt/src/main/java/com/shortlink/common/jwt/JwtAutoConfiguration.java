package com.shortlink.common.jwt;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * JWT 自动装配。
 *
 * <p>两个服务都依赖这个库：admin 用 {@link JwtIssuer} 发 token，gateway 用 {@link JwtVerifier} 校验。
 * 用自动装配而不是让调用方 {@code @Import}，与 common-web / common-webflux 的约定保持一致。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtIssuer jwtIssuer(JwtProperties properties) {
        return new JwtIssuer(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtVerifier jwtVerifier(JwtProperties properties) {
        return new JwtVerifier(properties);
    }
}
