package com.shoppilot.gateway.triage;

import com.shoppilot.tool.Intent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0 规则层的判定边界。重点是两类反例：
 * 反义提问不能被归成同一个意图，扫到实体必须走动态链路而不是缓存。
 */
class T0RuleLayerTest {

    private final T0RuleLayer layer = new T0RuleLayer();

    @ParameterizedTest
    @CsvSource({
            "七天无理由退货, POLICY_RETURN",
            "发什么快递, POLICY_SHIPPING",
            "跨店满减怎么算, POLICY_PROMO",
            "生鲜坏了怎么赔, POLICY_FRESH",
    })
    @DisplayName("政策咨询定案为准入意图")
    void classifiesPolicyQuestions(String query, Intent expected) {
        var result = layer.classify(query).orElseThrow();

        assertThat(result.intent()).isEqualTo(expected);
        assertThat(result.cacheAdmissible()).isTrue();
        assertThat(result.layer()).isEqualTo("T0");
    }

    @Test
    @DisplayName("通用政策问句准入，落到自己那一单的诉求不准入")
    void separatesGenericClauseFromPersonalCase() {
        var generic = layer.classify("七天无理由怎么退").orElseThrow();
        var personal = layer.classify("我的退货为什么被拒").orElseThrow();

        assertThat(generic.intent()).isEqualTo(Intent.POLICY_RETURN);
        assertThat(generic.cacheAdmissible()).isTrue();
        // 同一话题、第一人称表述：绝不能复用上面那条政策答案
        assertThat(personal.cacheAdmissible()).isFalse();
    }

    @Test
    @DisplayName("反义提问不共享同一个缓存桶")
    void distinguishesAdversarialPair() {
        var asking = layer.classify("怎么申请退货退款").orElseThrow();
        var complaining = layer.classify("我的退款为什么没到账").orElseThrow();

        assertThat(asking.cacheAdmissible()).isTrue();
        assertThat(complaining.cacheAdmissible()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "帮我查订单 10023, ACTION_ORDER",
            "10023 发货没, ACTION_LOGISTICS",
            "我的地址要改成望江路9号, ACTION_ADDRESS",
    })
    @DisplayName("带实体或第一人称动作的诉求走动态链路，绝不进缓存")
    void routesActionToIntDynamic(String query, Intent expected) {
        var result = layer.classify(query).orElseThrow();

        assertThat(result.intent()).isEqualTo(expected);
        assertThat(result.cacheAdmissible()).isFalse();
    }

    @Test
    @DisplayName("拿不准时不定案，交给下一级而不是猜一个")
    void staysSilentWhenUnsure() {
        assertThat(layer.classify("你们这个平台还行吧")).isEmpty();
    }
}
