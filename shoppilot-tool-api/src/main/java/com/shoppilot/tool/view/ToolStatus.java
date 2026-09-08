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
    /** 幂等重放：返回首次执行结果。 */
    IDEMPOTENT_REPLAY,
    /** 下游超时。 */
    TIMEOUT,
    /** 下游不可用或熔断打开。 */
    UNAVAILABLE
}
