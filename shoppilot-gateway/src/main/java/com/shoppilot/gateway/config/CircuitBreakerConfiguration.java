package com.shoppilot.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * biz-mock 调用的熔断器（ADR 0002、ADR 0008）。
 *
 * <p>单独成 bean 不是为了换实现，而是让票 29 能在同一个真实对象上挂状态 gauge
 * 与迁移计数器；{@link com.shoppilot.gateway.agent.BizMockClient} 继续只消费它的判定。
 */
@Configuration(proxyBeanMethods = false)
public class CircuitBreakerConfiguration {

    @Bean
    CircuitBreaker bizMockCircuitBreaker() {
        return CircuitBreaker.of("bizmock", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
    }
}
