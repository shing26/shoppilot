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
    @DisplayName("抖动后仍受 max 封顶：实际延迟不超过上限")
    void jitterStaysWithinConfiguredBand() {
        RoundBackoffPolicy policy =
                RoundBackoffPolicy.of(Duration.ofSeconds(1), 2, Duration.ofSeconds(1), 0.5);

        for (int i = 0; i < 100; i++) {
            long millis = policy.delay(1).toMillis();
            assertThat(millis).isBetween(500L, 1000L);
        }
    }

    @Test
    @DisplayName("非法配置被收敛：factor<1 不缩小、jitter>1 不越过归一化上限、负值归零")
    void invalidValuesAreNormalized() {
        RoundBackoffPolicy factorClamped =
                RoundBackoffPolicy.of(Duration.ofSeconds(10), 0.5, Duration.ofSeconds(40), 0);
        assertThat(factorClamped.delay(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(factorClamped.delay(2)).as("factor<1 收敛为 1，不应回落到 5s")
                .isEqualTo(Duration.ofSeconds(10));

        RoundBackoffPolicy jitterClamped =
                RoundBackoffPolicy.of(Duration.ofSeconds(10), 2, Duration.ofMinutes(1), 2);
        for (int i = 0; i < 200; i++) {
            assertThat(jitterClamped.delay(1).toMillis())
                    .as("jitter>1 收敛为 1，10s 基数的抖动上限是 20s")
                    .isBetween(0L, 20_000L);
        }

        RoundBackoffPolicy negatives =
                RoundBackoffPolicy.of(Duration.ofMillis(-1), 2, Duration.ofMillis(-1), 0);
        assertThat(negatives.delay(1)).isEqualTo(Duration.ZERO);
    }
}
