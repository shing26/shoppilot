package com.shoppilot.tool.view;

/**
 * 工具执行结果状态。失败不在模型层重试，而是把结构化事实回填给模型组织人话（ADR 0008）。
 */
public enum ToolStatus {
    OK,
    /** 订单不存在，或不属于当前会话的租户与买家（跨租户一律归入此项，不区分"存在但不可见"）。 */
    NOT_FOUND,
    /** 业务状态前置校验不通过，例如对已发货订单改地址。 */
    STATE_NOT_ALLOWED,
    /**
     * 需要人工审批的资金动作已受理，但尚未放行（ADR 0047）。
     *
     * <p>与 {@link #STATE_NOT_ALLOWED} 的区别是语义：这是**受理**不是**拒绝**（订单不会退回原状态），
     * 因此不能被当成"前步失败"中止 Plan；与 {@link #OK} 的区别是资金还没动 —— 复用 `OK` 会让
     * "已受理"与"已放行"在 `tool_result` 与 trace 里同形，审计时无法证明闸门存在。
     */
    PENDING_APPROVAL,
    /** 幂等重放：返回首次执行结果。 */
    IDEMPOTENT_REPLAY,
    /** 下游超时。 */
    TIMEOUT,
    /** 下游不可用或熔断打开。 */
    UNAVAILABLE
}
