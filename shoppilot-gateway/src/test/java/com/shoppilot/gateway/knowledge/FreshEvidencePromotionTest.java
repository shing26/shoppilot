package com.shoppilot.gateway.knowledge;

import com.shoppilot.tool.Intent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 生鲜时效条款被 fused-top-k 截掉时的定向证据提升（golden G-006/G-026）。 */
class FreshEvidencePromotionTest {

    @Test
    @DisplayName("生鲜理赔问句多保留一格证据，把排在第 6 的时限条款带进上下文")
    void givesFreshClaimQuestionAnExtraEvidenceSlot() {
        List<HybridRetriever.Retrieved> fused = List.of(
                rule("r1", "生鲜商品不支持七天无理由退货。"),
                rule("r2", "包装破损按责任分拆赔付。"),
                rule("r3", "赔付上限为商品实付金额。"),
                rule("r4", "申请需要提供开箱照片和订单号。"),
                rule("r5", "活鲜死亡按只数比例退款。"),
                rule("fresh-01", "生鲜理赔时限自物流显示签收的当日零点起算，常温果蔬四十八小时。"),
                rule("r7", "超出理赔时限申请商家有权不予受理。")
        );

        List<HybridRetriever.Retrieved> selected = HybridRetriever.selectTopK(
                fused, Intent.POLICY_FRESH, "生鲜坏了怎么赔", 5);

        assertThat(selected).hasSize(6);
        assertThat(selected.stream().map(HybridRetriever.Retrieved::ruleId))
                .contains("fresh-01")
                .doesNotContain("r7")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("非生鲜/非理赔问句保持原融合序，不误提")
    void leavesOtherQuestionsAlone() {
        List<HybridRetriever.Retrieved> fused = List.of(
                rule("r1", "生鲜理赔时限自签收起算。"),
                rule("r2", "包装破损按责任分拆赔付。")
        );

        List<HybridRetriever.Retrieved> selected = HybridRetriever.selectTopK(
                fused, Intent.POLICY_RETURN, "退货怎么退", 5);

        assertThat(selected).hasSize(2);
        assertThat(selected.get(0).ruleId()).isEqualTo("r1");
    }

    private static HybridRetriever.Retrieved rule(String id, String text) {
        return new HybridRetriever.Retrieved(id, 0.1d, 1, 1, "PLATFORM", "标题", "标题", "doc", text);
    }
}
