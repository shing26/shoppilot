package com.shoppilot.gateway.agent;

/**
 * 有界状态机的显式状态集合（ADR 0008）。
 *
 * <p>SSE 事件由状态转移直接驱动，不手写推送逻辑——这是自研状态机最实在的收益。
 */
public enum AgentState {
    INTAKE,
    TRIAGE,
    CACHE_READ,
    RETRIEVE,
    PLAN,
    TOOL_EXEC,
    SLOT_ASK,
    REPLY,
    CACHE_WRITE,
    FALLBACK;

    /** 面向用户的中文标签，用于 tool_executing 这类事件文案。 */
    public String label() {
        return switch (this) {
            case INTAKE -> "接收请求";
            case TRIAGE -> "识别意图";
            case CACHE_READ -> "查询缓存";
            case RETRIEVE -> "检索政策条款";
            case PLAN -> "组织回答";
            case TOOL_EXEC -> "调用业务系统";
            case SLOT_ASK -> "补充必要信息";
            case REPLY -> "生成回复";
            case CACHE_WRITE -> "写入缓存";
            case FALLBACK -> "转人工兜底";
        };
    }
}
