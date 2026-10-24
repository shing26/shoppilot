package com.shoppilot.gateway.orchestration;

import java.util.Optional;

/**
 * 显式 Handoff 有向图（ADR 0072）。
 *
 * <p>第一阶段只有一条边：{@code PRE_SALES -> AFTER_SALES}；初始路由不算 Handoff，
 * 当前深度从 0 起算。没有反向边，也没有“专家自己再找人”的自由协作。
 */
public final class HandoffGraph {

    public static final int MAX_HANDOFF_DEPTH = 1;
    /** 工单 payload 里的 policy 版本；现在只有这一份 ADR。 */
    public static final String POLICY_VERSION = "adr-0072";

    public record Decision(Optional<AgentRole> target, Optional<HandoffBlockReason> blockedReason) {

        public boolean allowed() {
            return target.isPresent();
        }

        public static Decision allow(AgentRole target) {
            return new Decision(Optional.of(target), Optional.empty());
        }

        public static Decision block(HandoffBlockReason reason) {
            return new Decision(Optional.empty(), Optional.of(reason));
        }
    }

    public Decision next(AgentRole from, AgentRole requested, int currentDepth) {
        if (from == requested) {
            return Decision.block(HandoffBlockReason.SAME_ROLE);
        }
        if (currentDepth >= MAX_HANDOFF_DEPTH) {
            return Decision.block(HandoffBlockReason.DEPTH_EXHAUSTED);
        }
        if (from == AgentRole.PRE_SALES && requested == AgentRole.AFTER_SALES) {
            return Decision.allow(AgentRole.AFTER_SALES);
        }
        return Decision.block(HandoffBlockReason.NO_EDGE);
    }
}
