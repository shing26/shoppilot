package com.shoppilot.gateway.agent;

/** 降级原因必须可枚举、可测，不是一句 catch（ADR 0009）。 */
public enum FallbackReason {
    LLM_TIMEOUT,
    LLM_CIRCUIT_OPEN,
    LLM_BUDGET_EXCEEDED,
    TOOL_UNAVAILABLE,
    INTENT_UNRESOLVED,
    RATE_LIMITED,
    SLOT_UNRESOLVED,
    USER_REQUESTED,
    TOOL_ROUNDS_EXHAUSTED,
    EMOTION_ESCALATION;

    public String userMessage() {
        return switch (this) {
            case LLM_TIMEOUT -> "智能回复响应有点慢，已为您转人工跟进。";
            case LLM_CIRCUIT_OPEN -> "智能回复暂时不可用，已为您登记人工处理。";
            case LLM_BUDGET_EXCEEDED -> "今日智能助手用量已达上限，已为您转人工。";
            case TOOL_UNAVAILABLE -> "业务系统响应异常，稍后我再帮您确认，已登记人工跟进。";
            case INTENT_UNRESOLVED -> "这个问题我需要人工客服帮您确认，已为您转接。";
            case RATE_LIMITED -> "当前咨询人数较多，请稍后再试。";
            case SLOT_UNRESOLVED -> "缺少必要信息无法为您办理，已转人工协助补充。";
            case USER_REQUESTED -> "已为您转接人工客服。";
            // 不复述计划、不承诺任何没执行的动作（ADR 0008「不猜不骗」）；已查到的事实由人工接手后可用
            case TOOL_ROUNDS_EXHAUSTED -> "这个问题需要人工为您整体跟进，已把已查到的信息交给人工客服。";
            // 先安抚再转接（ADR 0034）：买家正在气头上，认错话术优于效率话术
            case EMOTION_ESCALATION -> "实在抱歉让您有不好的体验，马上为您安排人工客服优先跟进。";
        };
    }
}
