package com.shoppilot.gateway.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 票 225 / ADR 0087：指数退避有增长、有上限、抖动不越界。 */
class RetryPolicyTest {

    @Test
    @DisplayName("间隔按 factor 增长并在上限处封顶")
    void delaysGrowThenCap() {
        RetryPolicy policy = RetryPolicy.of(3, Duration.ofMillis(100), 2, Duration.ofMillis(300), 0);

        assertThat(policy.delay(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delay(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.delay(3)).isEqualTo(Duration.ofMillis(300));
        assertThat(policy.delay(9)).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    @DisplayName("抖动只在上限的 ±jitter 内，不改变增长趋势")
    void jitterStaysWithinBand() {
        RetryPolicy policy = RetryPolicy.of(3, Duration.ofMillis(1000), 2, Duration.ofMillis(1000), 0.2);

        for (int i = 0; i < 100; i++) {
            long millis = policy.delay(1).toMillis();
            assertThat(millis).isBetween(800L, 1200L);
        }
    }

    @Test
    @DisplayName("退避参数有安全下限：attempts 至少 1，factor 至少 1")
    void invalidKnobsFallBack() {
        RetryPolicy policy = RetryPolicy.of(0, Duration.ofMillis(-5), 0.5, Duration.ofMillis(-1), -1);

        assertThat(policy.attempts()).isEqualTo(1);
        assertThat(policy.delay(1)).isEqualTo(Duration.ZERO);
    }
}
