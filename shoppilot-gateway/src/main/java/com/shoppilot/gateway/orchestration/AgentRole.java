package com.shoppilot.gateway.orchestration;

import com.shoppilot.tool.Intent;

/**
 * 专家角色（ADR 0072）。第一阶段只有两个角色；它裁剪的是**模型可见的工具候选面**，
 * 不是授权面——真正的鉴权、幂等、HITL 与审计仍在 {@code ToolDispatcher}。
 */
public enum AgentRole {

    AFTER_SALES("AFTER_SALES:EXISTING_ACTION_SURFACE"),
    PRE_SALES("PRE_SALES:READ_ONLY");

    private final String toolSurface;

    AgentRole(String toolSurface) {
        this.toolSurface = toolSurface;
    }

    /**
     * 确定性初始路由：售前只接物流与优惠两块纯咨询；其余意图保持现有售后链路。
     *
     * <p>这里不做 LLM 推理，也不猜业务：UNKNOWN 归 AFTER_SALES，让既有空召回/转人工出口兜底。
     */
    public static AgentRole routeFor(Intent intent) {
        return switch (intent) {
            case POLICY_SHIPPING, POLICY_PROMO -> PRE_SALES;
            default -> AFTER_SALES;
        };
    }

    /** 仅供 trace / 指标归因；不是授权判据。 */
    public String toolSurface() {
        return toolSurface;
    }

    /**
     * 售前角色的提示词段。刻意只有一句边界约束：不新增业务事实、不承诺政策。
     * AFTER_SALES 返回空串，保证既有售后链路的 Prompt 与 gold 口径零扰动。
     */
    public String promptSegment() {
        if (this != PRE_SALES) {
            return "";
        }
        return "【角色边界】你处于售前咨询角色，只回答配送、优惠等售前问题；"
                + "不能查订单、不能改地址、不能退款；买家要办理时说明需要转入售后处理。";
    }
}
