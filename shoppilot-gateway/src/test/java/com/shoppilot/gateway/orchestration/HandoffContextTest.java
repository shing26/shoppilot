package com.shoppilot.gateway.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** HandoffContext 是契约：老字段可忽略、关键事实可序列化、payload 受控裁剪。 */
class HandoffContextTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("JSON 往返保留角色、槽位、工具事实与 trail；未知字段不炸")
    void roundTripsContract() throws Exception {
        HandoffContext context = new HandoffContext(
                "conv-1", AgentRole.PRE_SALES, AgentRole.AFTER_SALES, "帮我查订单 90001",
                "ACTION_LOGISTICS", Map.of("orderNo", "90001"),
                List.of(new ToolExecutionRecord("queryOrderDetail", "OK", 1L, Map.of("orderNo", "90001"))),
                List.of(), PendingState.none(),
                List.of(HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 0, 2L)),
                "v1.2.0", "adr-0072");

        String json = mapper.writeValueAsString(context);
        assertThat(json).contains("AFTER_SALES").contains("90001").contains("ALLOWED");

        HandoffContext decoded = mapper.readValue(json + ",\"futureField\":true", HandoffContext.class);
        assertThat(decoded.to()).isEqualTo(AgentRole.AFTER_SALES);
        assertThat(decoded.confirmedSlots()).containsEntry("orderNo", "90001");
        assertThat(decoded.executedTools()).hasSize(1);
        assertThat(decoded.handoffTrail()).hasSize(1);
    }

    @Test
    @DisplayName("工单 payload 只带事实与受限 trail，不展开工具参数")
    void projectsBoundedTicketPayload() {
        HandoffContext context = new HandoffContext(
                "conv-1", AgentRole.PRE_SALES, AgentRole.AFTER_SALES, "x".repeat(600),
                "ACTION_LOGISTICS", Map.of("orderNo", "90001"),
                List.of(new ToolExecutionRecord("queryOrderDetail", "OK", 1L, Map.of("orderNo", "90001"))),
                List.of(), new PendingState(true, "queryOrderDetail", Map.of("orderNo", "90001"), 1),
                List.of(HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 0, 1L),
                        HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 1, 2L)),
                "v1.2.0", "adr-0072");

        Map<String, Object> payload = context.toTicketPayload();
        assertThat(payload)
                .containsEntry("route", "PRE_SALES->AFTER_SALES")
                .containsEntry("executedToolCount", 1)
                .containsEntry("pending", true);
        assertThat((String) payload.get("normalizedIntent")).hasSize(16);
        @SuppressWarnings("unchecked")
        List<Object> trail = (List<Object>) payload.get("handoffTrail");
        assertThat(trail).hasSize(2);
    }
}
