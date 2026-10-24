package com.shoppilot.gateway.orchestration;

import java.util.List;
import java.util.Optional;

/**
 * Handoff 触发器（ADR 0072 H1）：把“普通换话题”与“同诉求升级”分开。
 *
 * <p>Router 每轮都会重新给出请求角色；只有上一轮在售前、本轮需要售后工具、且图允许时，
 * 才算一次显式 Handoff。用户从售后话题切回售前是正常路由，不是 `AFTER_SALES -> PRE_SALES` 回切。
 */
public final class HandoffTrigger {

    public enum Kind {
        ROUTE_RESET,
        ALLOWED,
        BLOCKED
    }

    public record Decision(AgentRole effectiveRole, Kind kind, Optional<HandoffStep> step,
                           Optional<HandoffBlockReason> blockedReason) {

        public static Decision reset(AgentRole role) {
            return new Decision(role, Kind.ROUTE_RESET, Optional.empty(), Optional.empty());
        }
    }

    private final HandoffGraph graph;

    public HandoffTrigger() {
        this(new HandoffGraph());
    }

    public HandoffTrigger(HandoffGraph graph) {
        this.graph = graph;
    }

    public Decision evaluate(AgentRole previousRole, AgentRole requestedRole, boolean actionEvidence,
                             List<HandoffStep> trail, long atMillis) {
        if (previousRole == null || previousRole == requestedRole) {
            return Decision.reset(requestedRole);
        }
        if (previousRole != AgentRole.PRE_SALES || requestedRole != AgentRole.AFTER_SALES) {
            return Decision.reset(requestedRole);
        }
        if (!actionEvidence) {
            // 仍是咨询，只是被 Router 归到售后分区；不把政策话题升级成工具链路。
            return Decision.reset(requestedRole);
        }
        int depth = trail == null ? 0 : trail.size();
        var decision = graph.next(previousRole, requestedRole, depth);
        if (decision.allowed()) {
            return new Decision(requestedRole, Kind.ALLOWED,
                    Optional.of(HandoffStep.allowed(previousRole, requestedRole, depth, atMillis)),
                    Optional.empty());
        }
        // H1 不做 blocked 落单：本轮仍按请求角色服务，但不记 Handoff；H2 再接可查工单出口。
        return new Decision(requestedRole, Kind.BLOCKED, Optional.empty(), decision.blockedReason());
    }
}
