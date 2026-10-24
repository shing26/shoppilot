package com.shoppilot.gateway.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** H1：Handoff 只接“同诉求升级”；普通换话题一律 route reset。 */
class HandoffTriggerTest {

    private final HandoffTrigger trigger = new HandoffTrigger();

    @Test
    @DisplayName("上一轮售前 + 本轮需要售后工具 + 图允许 => ALLOWED")
    void escalatesSameNeedToAfterSales() {
        var decision = trigger.evaluate(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, true, List.of(), 1L);

        assertThat(decision.kind()).isEqualTo(HandoffTrigger.Kind.ALLOWED);
        assertThat(decision.effectiveRole()).isEqualTo(AgentRole.AFTER_SALES);
        assertThat(decision.step()).hasValueSatisfying(step -> {
            assertThat(step.from()).isEqualTo("PRE_SALES");
            assertThat(step.to()).isEqualTo("AFTER_SALES");
            assertThat(step.depth()).isZero();
            assertThat(step.result()).isEqualTo("ALLOWED");
        });
    }

    @Test
    @DisplayName("上一轮售前 + 本轮仍是咨询 => route reset，不建工具链路")
    void doesNotEscalateConsultation() {
        var decision = trigger.evaluate(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, false, List.of(), 1L);

        assertThat(decision.kind()).isEqualTo(HandoffTrigger.Kind.ROUTE_RESET);
        assertThat(decision.effectiveRole()).isEqualTo(AgentRole.AFTER_SALES);
        assertThat(decision.step()).isEmpty();
    }

    @Test
    @DisplayName("售后 -> 售前是普通换话题，不是回切 Handoff")
    void treatsReverseTopicChangeAsRouteReset() {
        var decision = trigger.evaluate(AgentRole.AFTER_SALES, AgentRole.PRE_SALES, false, List.of(), 1L);

        assertThat(decision.kind()).isEqualTo(HandoffTrigger.Kind.ROUTE_RESET);
        assertThat(decision.effectiveRole()).isEqualTo(AgentRole.PRE_SALES);
        assertThat(decision.step()).isEmpty();
    }

    @Test
    @DisplayName("同角色是普通路由，不是 Handoff")
    void treatsSameRoleAsRouteReset() {
        var decision = trigger.evaluate(AgentRole.AFTER_SALES, AgentRole.AFTER_SALES, true, List.of(), 1L);

        assertThat(decision.kind()).isEqualTo(HandoffTrigger.Kind.ROUTE_RESET);
        assertThat(decision.effectiveRole()).isEqualTo(AgentRole.AFTER_SALES);
    }

    @Test
    @DisplayName("首次路由没有 previous role => route reset")
    void treatsInitialRouteAsReset() {
        var decision = trigger.evaluate(null, AgentRole.PRE_SALES, false, List.of(), 1L);

        assertThat(decision.kind()).isEqualTo(HandoffTrigger.Kind.ROUTE_RESET);
        assertThat(decision.effectiveRole()).isEqualTo(AgentRole.PRE_SALES);
    }

    @Test
    @DisplayName("深度已用尽时不再 ALLOWED；H1 仍按请求角色服务，不落单")
    void reportsBlockedWithoutTicketInH1() {
        List<HandoffStep> trail = List.of(
                HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 0, 1L));
        var decision = trigger.evaluate(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, true, trail, 2L);

        assertThat(decision.kind()).isEqualTo(HandoffTrigger.Kind.BLOCKED);
        assertThat(decision.effectiveRole()).isEqualTo(AgentRole.AFTER_SALES);
        assertThat(decision.blockedReason()).contains(HandoffBlockReason.DEPTH_EXHAUSTED);
        assertThat(decision.step()).isEmpty();
    }
}
