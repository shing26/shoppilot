package com.shoppilot.gateway.cache;

import com.shoppilot.tool.Intent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写回资格（否决项）：降级话术、工具结果、无检索命中、短答案一律不许进缓存。
 *
 * <p>这一层失守的后果不是省不了钱，而是缓存开始高速复读道歉语，指标全绿而产品已坏。
 */
class WriteBackPolicyTest {

    private final WriteBackPolicy policy = new WriteBackPolicy();
    private static final String GOOD = "生鲜类商品到货即腐烂的，可在签收后二十四小时内申请全额退款。";

    @Test
    @DisplayName("政策意图 + 检索命中 + 正常长度答案才准入")
    void admitsGroundedPolicyAnswer() {
        var verdict = policy.evaluate(new WriteBackPolicy.Request(
                Intent.POLICY_FRESH, GOOD, true, false, false, false));

        assertThat(verdict.eligible()).isTrue();
    }

    @Test
    @DisplayName("降级话术被拒：这是本策略存在的唯一理由")
    void rejectsDegradedApology() {
        var verdict = policy.evaluate(new WriteBackPolicy.Request(
                Intent.POLICY_FRESH, "抱歉，系统繁忙，已为您转人工跟进处理，请稍后留意短信通知。",
                true, false, true, false));

        assertThat(verdict.eligible()).isFalse();
        assertThat(verdict.reason()).isEqualTo("degraded");
    }

    @Test
    @DisplayName("触发过工具的答案不写回：动态业务状态不能变成静态缓存")
    void rejectsToolBackedAnswer() {
        var verdict = policy.evaluate(new WriteBackPolicy.Request(
                Intent.POLICY_RETURN, GOOD, true, true, false, false));

        assertThat(verdict.eligible()).isFalse();
        assertThat(verdict.reason()).isEqualTo("tool-used");
    }

    @Test
    @DisplayName("检索无命中时不写回，避免把拒答固化成标准答案")
    void rejectsUngroundedAnswer() {
        var verdict = policy.evaluate(new WriteBackPolicy.Request(
                Intent.POLICY_PROMO, GOOD, false, false, false, false));

        assertThat(verdict.eligible()).isFalse();
        assertThat(verdict.reason()).isEqualTo("no-retrieval-hit");
    }

    @Test
    @DisplayName("过短答案不写回")
    void rejectsTooShortAnswer() {
        var verdict = policy.evaluate(new WriteBackPolicy.Request(
                Intent.POLICY_FRESH, "好的", true, false, false, false));

        assertThat(verdict.eligible()).isFalse();
        assertThat(verdict.reason()).isEqualTo("answer-too-short");
    }

    @ParameterizedTest
    @EnumSource(value = Intent.class, names = {"ACTION_ORDER", "ACTION_LOGISTICS", "ACTION_ADDRESS", "ACTION_REFUND",
            "ESCALATE", "UNKNOWN"})
    @DisplayName("动作意图与未定案一律不准入缓存")
    void rejectsNonAdmissibleIntents(Intent intent) {
        var verdict = policy.evaluate(new WriteBackPolicy.Request(
                intent, GOOD, true, false, false, false));

        assertThat(verdict.eligible()).isFalse();
        assertThat(verdict.reason()).isEqualTo("intent-not-admissible");
    }
}
