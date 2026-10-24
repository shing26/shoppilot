package com.shoppilot.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0072 H3：风险等级是共享契约元数据，且与既有审批闸门不冲突。 */
class ToolRiskTierTest {

    @Test
    @DisplayName("查询工具是 READ_ONLY，改地址是可逆低风险写")
    void classifiesReadAndLowRiskWrite() {
        assertThat(ToolName.QUERY_ORDER_DETAIL.riskTier()).isEqualTo(ToolRiskTier.READ_ONLY);
        assertThat(ToolName.QUERY_LOGISTICS.riskTier()).isEqualTo(ToolRiskTier.READ_ONLY);
        assertThat(ToolName.MODIFY_DELIVERY_ADDRESS.riskTier()).isEqualTo(ToolRiskTier.LOW_RISK_WRITE);
        assertThat(ToolName.MODIFY_DELIVERY_ADDRESS.requiresHitl()).isFalse();
    }

    @Test
    @DisplayName("退款是 MONEY_WRITE，必须 HITL；当前不存在 IRREVERSIBLE 工具")
    void classifiesMoneyWriteAndKeepsHitl() {
        assertThat(ToolName.APPLY_REFUND.riskTier()).isEqualTo(ToolRiskTier.MONEY_WRITE);
        assertThat(ToolName.APPLY_REFUND.requiresHitl()).isTrue();
        assertThat(ToolName.APPLY_REFUND.requiresApproval()).isTrue();
        for (ToolName tool : ToolName.values()) {
            assertThat(tool.riskTier()).isNotEqualTo(ToolRiskTier.IRREVERSIBLE_WRITE);
        }
    }
}
