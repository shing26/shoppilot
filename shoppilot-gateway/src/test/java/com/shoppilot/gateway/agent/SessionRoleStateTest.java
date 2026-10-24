package com.shoppilot.gateway.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppilot.gateway.orchestration.AgentRole;
import com.shoppilot.gateway.orchestration.HandoffStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shoppilot.gateway.config.GatewayProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** H1：旧 session 兼容、新路由字段持久化、appendTurn 不丢编排状态。 */
class SessionRoleStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("旧 JSON 缺 currentRole/handoffTrail 时按未路由读取")
    void readsLegacySession() throws Exception {
        String legacy = """
                {"conversationId":"conv-1","turns":[],"pendingTool":null,
                 "pendingArgs":{},"slotAskCount":0}
                """;
        SessionStore.Session session = mapper.readValue(legacy, SessionStore.Session.class);

        assertThat(session.currentRole()).isNull();
        assertThat(session.handoffTrail()).isEmpty();
    }

    @Test
    @DisplayName("新 JSON 往返保留角色与 trail，未知字段不炸")
    void roundTripsRoutingState() throws Exception {
        SessionStore.Session session = SessionStore.Session.empty("conv-1")
                .withRouting(AgentRole.AFTER_SALES,
                        List.of(HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 0, 1L)));
        ObjectNode json = (ObjectNode) mapper.valueToTree(session);
        json.put("futureField", true);
        SessionStore.Session decoded = mapper.treeToValue(json, SessionStore.Session.class);

        assertThat(decoded.currentRole()).isEqualTo(AgentRole.AFTER_SALES);
        assertThat(decoded.handoffTrail()).hasSize(1);
    }

    @Test
    @DisplayName("withRouting 截断过长 trail；appendTurn / clearPending 不丢路由状态")
    void preservesRoutingAcrossSessionUpdates() {
        SessionStore.Session session = SessionStore.Session.empty("conv-1")
                .withRouting(AgentRole.AFTER_SALES,
                        List.of(HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 0, 1L),
                                HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 1, 2L),
                                HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 2, 3L),
                                HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 3, 4L),
                                HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 4, 5L),
                                HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 5, 6L)));

        assertThat(session.handoffTrail()).hasSize(5);

        GatewayProperties.Agent agent = new GatewayProperties.Agent(4, 2, Duration.ofHours(1), 10);
        GatewayProperties properties = new GatewayProperties(null, null, null, null, null, null,
                agent, null, null, null, null, null, null);
        SessionStore.Session appended = new SessionStore(null, new ObjectMapper(), properties).appendTurn(
                session, "你好", "你好，需要帮忙吗？");
        assertThat(appended.currentRole()).isEqualTo(AgentRole.AFTER_SALES);
        assertThat(appended.handoffTrail()).hasSize(5);

        SessionStore.Session cleared = new SessionStore.Session(session.conversationId(), session.turns(), null,
                new LinkedHashMap<>(), 0, session.currentRole(), session.handoffTrail());
        assertThat(cleared.pendingTool()).isNull();
        assertThat(cleared.currentRole()).isEqualTo(AgentRole.AFTER_SALES);
        assertThat(cleared.handoffTrail()).hasSize(5);
    }
}
