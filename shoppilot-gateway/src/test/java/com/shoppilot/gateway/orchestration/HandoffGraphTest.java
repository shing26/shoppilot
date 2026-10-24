package com.shoppilot.gateway.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0072：一张图、两个角色、一条边、一个硬上限。 */
class HandoffGraphTest {

    private final HandoffGraph graph = new HandoffGraph();

    @Test
    @DisplayName("只允许 PRE_SALES -> AFTER_SALES 这一条边")
    void allowsOnlyTheDeclaredEdge() {
        var allowed = graph.next(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 0);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.target()).contains(AgentRole.AFTER_SALES);

        var reverse = graph.next(AgentRole.AFTER_SALES, AgentRole.PRE_SALES, 0);
        assertThat(reverse.allowed()).isFalse();
        assertThat(reverse.blockedReason()).contains(HandoffBlockReason.NO_EDGE);
    }

    @Test
    @DisplayName("深度从 0 起算，最大 1；超限不继续推理")
    void enforcesDepthHardLimit() {
        assertThat(graph.next(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 0).allowed()).isTrue();
        var exhausted = graph.next(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 1);
        assertThat(exhausted.allowed()).isFalse();
        assertThat(exhausted.blockedReason()).contains(HandoffBlockReason.DEPTH_EXHAUSTED);
    }

    @Test
    @DisplayName("同角色不是 Handoff")
    void treatsSameRoleAsNoHandoff() {
        var same = graph.next(AgentRole.AFTER_SALES, AgentRole.AFTER_SALES, 0);
        assertThat(same.allowed()).isFalse();
        assertThat(same.blockedReason()).contains(HandoffBlockReason.SAME_ROLE);
    }
}
