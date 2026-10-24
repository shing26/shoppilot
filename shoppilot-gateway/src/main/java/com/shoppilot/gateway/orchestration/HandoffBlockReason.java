package com.shoppilot.gateway.orchestration;

/** Handoff 被拒的原因；只允许封闭枚举，避免指标标签基数失控。 */
public enum HandoffBlockReason {
    SAME_ROLE,
    NO_EDGE,
    DEPTH_EXHAUSTED
}
