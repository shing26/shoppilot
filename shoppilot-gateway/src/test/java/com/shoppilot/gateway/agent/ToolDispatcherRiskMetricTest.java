package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.orchestration.AgentRole;
import com.shoppilot.gateway.orchestration.RouteContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** H3：每次工具尝试都留下风险面观测；actor 只进日志，不进指标标签。 */
class ToolDispatcherRiskMetricTest {

    private SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void cleanContexts() {
        TenantContext.clear();
        RouteContext.clear();
    }

    @Test
    @DisplayName("READ_ONLY 缺槽尝试也记录 tool/risk/role；不要求 HITL")
    void recordsReadOnlyAttempt() {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-1"));
        RouteContext.set(new RouteContext.Snapshot(AgentRole.AFTER_SALES, java.util.List.of(),
                java.util.Map.of(), "ACTION_ORDER", null, "v1.2.0", "adr-0072"));
        ToolDispatcher dispatcher = new ToolDispatcher(null, null, registry);

        var dispatch = dispatcher.dispatch(new LlmTypes.ToolCall("test", "queryOrderDetail",
                java.util.Map.of()), "token", "订单 90001");

        assertThat(dispatch.needsSlot()).isTrue();
        assertThat(registry.get("shoppilot_tool_risk_attempts_total")
                .tag("tool", "QUERY_ORDER_DETAIL")
                .tag("risk", "READ_ONLY")
                .tag("hitl", "false")
                .tag("role", "AFTER_SALES")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("MONEY_WRITE 记录 hitl=true；未路由角色记 UNROUTED")
    void recordsMoneyWriteAttempt() {
        RouteContext.set(new RouteContext.Snapshot(AgentRole.PRE_SALES, java.util.List.of(),
                java.util.Map.of(), "ACTION_REFUND", null, "v1.2.0", "adr-0072"));
        ToolDispatcher dispatcher = new ToolDispatcher(null, null, registry);

        var dispatch = dispatcher.dispatch(new LlmTypes.ToolCall("test", "applyRefund",
                java.util.Map.of()), "token", "订单 90002 退款");

        assertThat(dispatch.needsSlot()).isTrue();
        assertThat(registry.get("shoppilot_tool_risk_attempts_total")
                .tag("tool", "APPLY_REFUND")
                .tag("risk", "MONEY_WRITE")
                .tag("hitl", "true")
                .tag("role", "PRE_SALES")
                .counter().count()).isEqualTo(1.0);
    }
}
