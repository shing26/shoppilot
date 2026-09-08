package com.shoppilot.gateway.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 极性守卫用例（ticket 17）。
 *
 * <p>这组用例存在的理由是实测数据：0.95 阈值下「这个能退吗」与「这个是不是不能退」余弦 0.9682，
 * 且两者同属 POLICY_RETURN、都在缓存准入范围内，阈值与意图分区双双失效。
 */
class PolarityGuardTest {

    @Test
    void blocksTheAntonymPairThatCrossedTheThreshold() {
        assertThat(PolarityGuard.blocked("这个是不是不能退", "这个能退吗")).isEqualTo("polarity-conflict");
        assertThat(PolarityGuard.blocked("这个能退吗", "这个是不是不能退")).isEqualTo("polarity-conflict");
    }

    @Test
    void allowsSamePolarityParaphrases() {
        assertThat(PolarityGuard.blocked("这个能退吗", "可以退货不")).isNull();
        assertThat(PolarityGuard.blocked("这个不能退吗", "是不是不给退")).isNull();
    }

    @Test
    void failClosedWhenTheStoredQueryIsMissing() {
        // 老条目没有 query 字段：宁可多打穿一次模型，也不复用一条无法校验的答案
        assertThat(PolarityGuard.blocked("这个能退吗", null)).isEqualTo("polarity-unknown");
        assertThat(PolarityGuard.blocked("这个能退吗", "")).isEqualTo("polarity-unknown");
    }

    @Test
    void treatsANotAQuestionAsNeutralSoItNeverBlocks() {
        assertThat(PolarityGuard.blocked("能不能退", "可以退吗")).isNull();
        assertThat(PolarityGuard.blocked("可以退吗", "能不能退")).isNull();
    }

    @Test
    void samePolarityIsAlwaysReusable() {
        assertThat(PolarityGuard.blocked("这个能退吗", "这个能退吗")).isNull();
    }
}
