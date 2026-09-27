package com.shoppilot.tool;

import com.shoppilot.tool.view.ToolResponse;
import com.shoppilot.tool.view.ToolStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0047 的契约面：声明式审批策略只覆盖资金/不可逆动作，受理态是一个独立的状态值。
 */
class ToolContractApprovalTest {

    @Test
    void onlyIrreversibleMoneyActionsRequireApproval() {
        assertThat(ToolName.APPLY_REFUND.requiresApproval()).isTrue();
        // 改地址写 address_history、有版本、可再改回 —— 不是不可逆动作，不入闸门
        assertThat(ToolName.MODIFY_DELIVERY_ADDRESS.requiresApproval()).isFalse();
        assertThat(ToolName.QUERY_ORDER_DETAIL.requiresApproval()).isFalse();
        assertThat(ToolName.QUERY_LOGISTICS.requiresApproval()).isFalse();
    }

    @Test
    void pendingApprovalIsItsOwnStatusAndNotASuccess() {
        ToolResponse<Map<String, String>> response =
                ToolResponse.pendingApproval("applyRefund", Map.of("status", "PENDING_REVIEW"));

        assertThat(response.status()).isEqualTo(ToolStatus.PENDING_APPROVAL);
        assertThat(response.payload()).isNotNull();
        // 受理不是"办成"：succeeded() 保持只认 OK / IDEMPOTENT_REPLAY，避免涟漪到所有调用点
        assertThat(response.succeeded()).isFalse();
        // BizMockClient.interpret 用 valueOf 解析状态串，新值必须能被直接反解
        assertThat(ToolStatus.valueOf("PENDING_APPROVAL")).isEqualTo(ToolStatus.PENDING_APPROVAL);
    }
}
