package com.shoppilot.tool;

/**
 * 意图清单是缓存分区、工具裁剪、限流统计与评测集共用的唯一枚举（ADR 0007）。
 * 术语定义见仓库根目录 CONTEXT.md，新增意图需同步上述四处。
 */
public enum Intent {
    POLICY_RETURN,
    POLICY_SHIPPING,
    POLICY_PROMO,
    POLICY_FRESH,
    ACTION_ORDER,
    ACTION_LOGISTICS,
    ACTION_ADDRESS,
    ACTION_REFUND,
    /** 系统能力边界之外的显式出口，不是失败。 */
    ESCALATE,
    /** 判定不出的诉求：fail-closed，不进缓存。 */
    UNKNOWN;

    public boolean isPolicy() {
        return name().startsWith("POLICY_");
    }

    public boolean isAction() {
        return name().startsWith("ACTION_");
    }

    /**
     * 缓存准入的意图侧条件：只有政策咨询可准入。
     * 最终是否准入还取决于实体扫描与会话上下文，见 ADR 0003。
     */
    public boolean cacheAdmissible() {
        return isPolicy();
    }

    /** 多意图冲突时动作优先（ADR 0007）。 */
    public static Intent actionWins(Intent a, Intent b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.isAction() && !a.isAction() ? b : a;
    }
}
