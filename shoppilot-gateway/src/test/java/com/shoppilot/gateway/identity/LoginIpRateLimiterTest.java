package com.shoppilot.gateway.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 登录每 IP 限流（round56 票 224 / ADR 0086 §4）。 */
class LoginIpRateLimiterTest {

    @Test
    void sameIpIsLimitedAfterConfiguredFailures() {
        LoginIpRateLimiter limiter = new LoginIpRateLimiter();
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.blocked("127.0.0.1")).as("第 %d 次失败前", i + 1).isFalse();
            limiter.recordFailure("127.0.0.1");
        }
        assertThat(limiter.blocked("127.0.0.1")).isTrue();
        assertThat(limiter.blocked("127.0.0.2")).as("别的 IP 不受影响").isFalse();
    }
}
