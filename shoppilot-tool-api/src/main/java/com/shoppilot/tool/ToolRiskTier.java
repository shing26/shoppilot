package com.shoppilot.tool;

/**
 * 工具爆炸半径（ADR 0072 H3）。它是静态契约元数据，不由模型判断；
 * 角色工具面只决定“能否看到候选”，执行授权与 HITL 仍由运行时强制。
 */
public enum ToolRiskTier {
    /** 只读查询：不改变业务状态。 */
    READ_ONLY,
    /** 可逆或低风险写操作：有版本 / 可再次修改。 */
    LOW_RISK_WRITE,
    /** 涉资写操作：必须过 HITL / 审批。 */
    MONEY_WRITE,
    /** 不可逆写操作：当前工具集没有实例；为后续治理预留封闭枚举。 */
    IRREVERSIBLE_WRITE
}
