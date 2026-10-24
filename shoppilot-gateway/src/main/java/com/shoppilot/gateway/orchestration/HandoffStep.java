package com.shoppilot.gateway.orchestration;

/**
 * 一次显式 Handoff 的事实记录。只记录**发生了什么**，不携带业务机密；
 * 进 trace / 工单 payload 前由调用方裁剪长度。
 */
public record HandoffStep(String from, String to, int depth, String result, long atMillis) {

    public static HandoffStep allowed(AgentRole from, AgentRole to, int depth, long atMillis) {
        return new HandoffStep(from.name(), to.name(), depth, "ALLOWED", atMillis);
    }

    public static HandoffStep blocked(AgentRole from, AgentRole to, int depth, String reason, long atMillis) {
        return new HandoffStep(from.name(), to.name(), depth, "BLOCKED:" + reason, atMillis);
    }
}
