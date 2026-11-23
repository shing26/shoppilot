package com.shoppilot.gateway.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RoundBackoffPolicyTest {

    @Test
    @DisplayName("跨轮退避按 base × factor^(n-1) 增长并受 max 封顶")
    void delayGrowsExponentiallyAndCaps() {
        RoundBackoffPolicy policy =
                RoundBackoffPolicy.of(Duration.ofSeconds(5), 2, Duration.ofSeconds(30), 0);

        assertThat(policy.delay(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delay(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delay(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.delay(4)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delay(99)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("抖动只在上限 ±jitter 内波动，不改变封顶")
    void jitterStaysWithinConfiguredBand() {
        RoundBackoffPolicy policy =
                RoundBackoffPolicy.of(Duration.ofSeconds(1), 2, Duration.ofSeconds(1), 0.5);

        for (int i = 0; i < 100; i++) {
            long millis = policy.delay(1).toMillis();
            assertThat(millis).isBetween(500L, 1500L);
        }
    }

    @Test
    @DisplayName("非法配置被收敛：负 base / 小于 1 的 factor / 负 max / 越界 jitter")
    void invalidValuesAreNormalized() {
        RoundBackoffPolicy policy =
                RoundBackoffPolicy.of(Duration.ofMillis(-1), 0.5, Duration.ofMillis(-1), 2);

        assertThat(policy.delay(1)).isEqualTo(Duration.ZERO);
        assertThat(policy.delay(10)).isEqualTo(Duration.ZERO);
    }
}
