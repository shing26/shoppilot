package com.shoppilot.gateway.triage;

import com.shoppilot.gateway.knowledge.Domain;
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
            "几点可以办理入住, POLICY_HOTEL",
            "退房时间是几点, POLICY_HOTEL",
            "订错了能免费取消吗, POLICY_HOTEL",
            "早餐是几点到几点, POLICY_HOTEL",
    })
    @DisplayName("政策咨询定案为准入意图")
    void classifiesPolicyQuestions(String query, Intent expected) {
        var result = layer.classify(query).orElseThrow();

        assertThat(result.intent()).isEqualTo(expected);
        assertThat(result.cacheAdmissible()).isTrue();
        assertThat(result.layer()).isEqualTo("T0");
    }

    // ---- R7：T002 门店细则的「看照片不能定责」升级 -------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "T002, 死蟹了，算谁的责任",
            "T002, 死蟹了赔不赔",
            "T002, 大闸蟹到货死了，是不是你们的责任",
            "T002, 死蟹能按只补偿吗",
            "T002, 90001 这单死蟹了，你们负责吗",
    })
    @DisplayName("R7：T002 的活鲜定责诉求在 T0 定案为 policy-human 升级（不查单、不调模型）")
    void t002LiabilityRequestsArePolicyHumanEscalations(String tenant, String query) {
        var result = layer.classify(query, Domain.ECOMMERCE, tenant).orElseThrow();

        assertThat(result.intent()).isEqualTo(Intent.ESCALATE);
        assertThat(result.policyHuman()).isTrue();
        assertThat(result.cacheAdmissible()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "T002, 死蟹赔付标准是什么",
            "T002, 死蟹怎么申请赔付",
            "T002, 冻品化冻了算谁的责任",
            "T002, 水果变质了要提供照片吗",
            "T001, 冻品化冻了算谁的责任",
            "T001, 死蟹了，算谁的责任",
    })
    @DisplayName("R7 反证：政策/流程问法与非 T002 租户不升级——gold 的 T001 判定面不变，规则不越界")
    void nonLiabilityOrOtherTenantsStayPolicy(String tenant, String query) {
        var result = layer.classify(query, Domain.ECOMMERCE, tenant);

        assertThat(result.filter(r -> r.intent() == Intent.ESCALATE)).isEmpty();
        assertThat(result.filter(TriageResult::policyHuman)).isEmpty();
    }

    @Test
    @DisplayName("round33：酒店租户的酒店问句即使带第一人称也判政策（行业门在实体/第一人称之前）")
    void hotelWordsWinOverPossessiveSoNoShopToolsAreAttached() {
        var hotel = layer.classify("我订的房能免费取消吗", Domain.HOTEL).orElseThrow();

        assertThat(hotel.intent()).isEqualTo(Intent.POLICY_HOTEL);
        assertThat(hotel.cacheAdmissible()).isTrue();
        // 工具裁剪按 isAction() 分流（AgentStateMachine:728）：政策意图挂零个工具。
        // 这一条是那件事在判定层的可断言面——酒店问句绝不能拿到 QUERY_ORDER。
        assertThat(hotel.intent().isAction()).isFalse();
    }

    @Test
    @DisplayName("反证：同一句去掉酒店词就回到动态动作 —— 说明上面那条真的是酒店词表在起作用")
    void withoutHotelWordsThePossessiveGoesBackToDynamic() {
        // 电商租户：去掉酒店词之后，"我订的…" 命中第一人称 → 动态动作
        var withoutHotelWord = layer.classify("我订的东西到哪了").orElseThrow();
        assertThat(withoutHotelWord.intent()).isEqualTo(Intent.ACTION_LOGISTICS);
        assertThat(withoutHotelWord.cacheAdmissible()).isFalse();

        // 酒店租户：同一句不带酒店词 → 行业门之后不定案，交给下一级（工具表为空）
        assertThat(layer.classify("我订的东西到哪了", Domain.HOTEL)).isEmpty();
    }

    // ---- round33 / ADR 0067：动作优先、行业门、泛词反证 -------------------------------------

    @Test
    @DisplayName("round33 动作优先：没有实体也没有第一人称的显式办理诉求仍判动作，不落政策")
    void explicitActionPhraseBeatsPolicyWords() {
        var refund = layer.classify("生鲜坏了我要退款").orElseThrow();
        assertThat(refund.intent()).isEqualTo(Intent.ACTION_REFUND);
        assertThat(refund.cacheAdmissible()).isFalse();

        var address = layer.classify("地址填错了我要改地址").orElseThrow();
        assertThat(address.intent()).isEqualTo(Intent.ACTION_ADDRESS);
        assertThat(address.cacheAdmissible()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "退款政策是什么, POLICY_RETURN",
            "这个还能退吗, POLICY_RETURN",
            "七天无理由怎么退, POLICY_RETURN",
    })
    @DisplayName("反证：纯政策问法不许被上面那条误判成动作 —— 否则政策咨询会被拖进工具循环")
    void purePolicyQuestionsStayPolicy(String query, Intent expected) {
        var result = layer.classify(query).orElseThrow();

        assertThat(result.intent()).isEqualTo(expected);
        assertThat(result.cacheAdmissible()).isTrue();
    }

    @Test
    @DisplayName("round33 行业门：酒店租户的电商办理/政策问句不落电商意图，留给下一级")
    void hotelDomainGateRejectsEcommerceIntents() {
        // D5：旧实现把"我要取消订单"判成 ACTION_REFUND 并挂上 APPLY_REFUND
        assertThat(layer.classify("我要取消订单", Domain.HOTEL)).isEmpty();
        // D2/D3：酒店租户问生鲜，不许落 POLICY_FRESH
        assertThat(layer.classify("生鲜坏了怎么赔", Domain.HOTEL)).isEmpty();
        // 酒店自己的问句照常定案
        assertThat(layer.classify("押金什么时候退", Domain.HOTEL).orElseThrow().intent())
                .isEqualTo(Intent.POLICY_HOTEL);
        // 显式转人工优先级最高，行业门不能把它挡掉
        assertThat(layer.classify("我要转人工", Domain.HOTEL).orElseThrow().intent())
                .isEqualTo(Intent.ESCALATE);
    }

    @ParameterizedTest
    @CsvSource({
            "手机坏了怎么赔",
            "早餐机坏了怎么赔",
            "家电破损了怎么理赔",
    })
    @DisplayName("round33 泛词反证：去掉了行业对象词的泛问句不再被 T0 判成生鲜/酒店")
    void genericWordsNoLongerTriggerIndustryPolicies(String query) {
        assertThat(layer.classify(query)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "早餐是几点到几点",
            "早餐几点开始",
            "早餐时间",
    })
    @DisplayName("round33 修 HTL-BKF-01：餐饮语境的「早餐」判酒店，两个域一致")
    void breakfastTimeQuestionIsHotelPolicy(String query) {
        // 电商租户问酒店早餐属于越界咨询：T0 仍给 POLICY_HOTEL，检索 domain 过滤后转人工
        assertThat(layer.classify(query, Domain.ECOMMERCE).orElseThrow().intent())
                .isEqualTo(Intent.POLICY_HOTEL);
        // 酒店租户走行业门，同一句话同样定案
        assertThat(layer.classify(query, Domain.HOTEL).orElseThrow().intent())
                .isEqualTo(Intent.POLICY_HOTEL);
        // 负向断言只放行餐饮语境：家电名里的「早餐机」不判酒店（D6 不回归）
        assertThat(layer.classify("早餐机坏了怎么赔", Domain.HOTEL)).isEmpty();
    }

    @Test
    @DisplayName("round32：酒店新意图是政策意图，缓存分区自动跟着 isPolicy() 走")
    void hotelIntentIsCacheAdmissibleByConstruction() {
        assertThat(Intent.POLICY_HOTEL.isPolicy()).isTrue();
        assertThat(Intent.POLICY_HOTEL.cacheAdmissible()).isTrue();
        assertThat(Intent.POLICY_HOTEL.isAction()).isFalse();
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

    @ParameterizedTest
    @CsvSource({"你好", "您好！", "hi", "hello", "在吗", "你好呀"})
    @DisplayName("纯问候在 T0 定案为 UNKNOWN，不落 ESCALATE/人工工单（G-023）")
    void pureGreetingsAreNotEscalations(String query) {
        var result = layer.classify(query).orElseThrow();

        assertThat(result.intent()).isEqualTo(Intent.UNKNOWN);
        assertThat(result.cacheAdmissible()).isFalse();
    }

    @Test
    @DisplayName("问候语后面真的要求人工时，仍先命中显式转人工")
    void greetingWithExplicitEscalationStillEscalates() {
        var result = layer.classify("你好，转人工").orElseThrow();

        assertThat(result.intent()).isEqualTo(Intent.ESCALATE);
    }

    @Test
    @DisplayName("拿不准时不定案，交给下一级而不是猜一个")
    void staysSilentWhenUnsure() {
        assertThat(layer.classify("你们这个平台还行吧")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "转人工",
            "我要转个人工",
            "叫你们人工客服来",
            "这个问题机器人解决不了吧，转接人工",
            // 票 163：口语化升级，不能因为没说“转人工”三个字就漏掉
            "这个问题我要真人给我答复",
            "给我接一个能拍板的客服",
            "满减算错了我要求赔偿，机器人办不了就叫人",
            "这事没个活人说不清楚",
    })
    @DisplayName("显式转人工在 T0 定案：判定不依赖 embedding，模型或向量服务挂了也能转出去")
    void explicitEscalationSettlesAtT0(String query) {
        var result = layer.classify(query).orElseThrow();

        assertThat(result.intent()).isEqualTo(Intent.ESCALATE);
        assertThat(result.layer()).isEqualTo("T0");
        assertThat(result.cacheAdmissible()).isFalse();
    }

    @Test
    @DisplayName("显式转人工优先于实体与第一人称")
    void escalationBeatsEntityAndPossessive() {
        assertThat(layer.classify("90001 这单搞错了，转人工").orElseThrow().intent())
                .isEqualTo(Intent.ESCALATE);
        assertThat(layer.classify("我要投诉，给我转人工").orElseThrow().intent())
                .isEqualTo(Intent.ESCALATE);
    }

    @Test
    @DisplayName("否定形式的转人工不触发转人工，且否定词只看紧邻窗口")
    void negatedEscalationDoesNotFire() {
        assertThat(layer.classify("别转人工，我就要机器人回")).isEmpty();
        assertThat(layer.classify("我不想转人工，先说清楚规则")).isEmpty();
        // 票 163：新增口语词也守否定；“别叫人”“不想找真人”不是升级
        assertThat(layer.classify("别叫人，先让机器人把规则说完")).isEmpty();
        assertThat(layer.classify("我不想找真人，先看政策")).isEmpty();
        // 中间隔了逗号也要判成转人工：整句扫否定词会把这句漏掉
        assertThat(layer.classify("这个不合规，转人工").orElseThrow().intent())
                .isEqualTo(Intent.ESCALATE);
    }

    @Test
    @DisplayName("政策形状兜底：一般问答不被误判，政策诉求即使 UNKNOWN 仍能走检索")
    void policyShapeGuard() {
        assertThat(layer.looksPolicyShaped("店铺推荐什么商品")).isFalse();
        assertThat(layer.looksPolicyShaped("你们店是卖什么的？")).isFalse();
        assertThat(layer.looksPolicyShaped("有哪些售后保障？")).isTrue();
        assertThat(layer.looksPolicyShaped("生鲜坏了怎么赔")).isTrue();
        assertThat(layer.looksPolicyShaped("入住和退房时间是几点？")).isTrue();
        assertThat(layer.looksPolicyShaped(null)).isFalse();
        // 形状命中后尽量给具体分区；泛化的「售后保障」没有具体分区，交给确定性追问。
        assertThat(layer.policyHintIntent("有哪些售后保障？")).isNull();
        assertThat(layer.policyHintIntent("入住和退房时间是几点？")).isEqualTo(Intent.POLICY_HOTEL);
        assertThat(layer.policyHintIntent("店铺推荐什么商品")).isNull();
    }
}
