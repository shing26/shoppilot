package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.orchestration.AgentRole;
import com.shoppilot.gateway.orchestration.HandoffBlockReason;
import com.shoppilot.gateway.orchestration.HandoffGraph;
import com.shoppilot.gateway.orchestration.HandoffStep;
import com.shoppilot.gateway.orchestration.RouteContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** H2：blocked Handoff 工单 payload 的 schema 与脱敏边界。 */
class FallbackOrchestrationPayloadTest {

    @Test
    @DisplayName("未跑到路由层时不伪造编排字段")
    void omitsOrchestrationWhenUnrouted() {
        assertThat(FallbackService.orchestrationPayload(RouteContext.snapshot())).isEmpty();
        assertThat(FallbackService.orchestrationPayload(null)).isEmpty();
    }

    @Test
    @DisplayName("blocked 落单带 trail / blockedReason / confirmedSlots，不展开工具参数")
    void projectsBlockedHandoffFacts() {
        RouteContext.Snapshot route = new RouteContext.Snapshot(
                AgentRole.AFTER_SALES,
                List.of(HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 0, 1L)),
                Map.of("orderNo", "90001", "receiverPhone", "138****1234"),
                "ACTION_LOGISTICS", HandoffBlockReason.DEPTH_EXHAUSTED,
                "v1.2.0", HandoffGraph.POLICY_VERSION);

        Map<String, Object> payload = FallbackService.orchestrationPayload(route);

        assertThat(payload)
                .containsEntry("agentRole", "AFTER_SALES")
                .containsEntry("toolSurface", AgentRole.AFTER_SALES.toolSurface())
                .containsEntry("blockedReason", "DEPTH_EXHAUSTED")
                .containsEntry("normalizedIntent", "ACTION_LOGISTICS")
                .containsEntry("promptVersion", "v1.2.0")
                .containsEntry("policyVersion", HandoffGraph.POLICY_VERSION);
        assertThat(payload.get("handoffTrail")).asList().hasSize(1);
        assertThat(payload.get("confirmedSlots")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("orderNo", "90001")
                .containsEntry("receiverPhone", "138****1234");
    }

    @Test
    @DisplayName("普通路由降级不带 blockedReason")
    void omitsBlockedReasonForNormalRoute() {
        RouteContext.Snapshot route = new RouteContext.Snapshot(AgentRole.PRE_SALES, List.of(),
                Map.of(), "POLICY_SHIPPING", null, "v1.2.0", HandoffGraph.POLICY_VERSION);

        Map<String, Object> payload = FallbackService.orchestrationPayload(route);

        assertThat(payload).doesNotContainKey("blockedReason").doesNotContainKey("handoffTrail");
        assertThat(payload).containsEntry("agentRole", "PRE_SALES");
    }
}
