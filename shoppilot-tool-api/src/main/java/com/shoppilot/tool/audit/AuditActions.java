package com.shoppilot.tool.audit;

/**
 * 审计动作（round23 票 71 / ADR 0056）。命名用「对象 + 动作」，读的人不看代码也知道发生了什么。
 *
 * <p>本清单只收**不可逆或要留痕**的动作。普通读、单据状态的每一次流转不进这里——审计一旦
 * 什么都记，它就查不动了；被逼着记流水账的审计，最后没人看。
 */
public final class AuditActions {

    private AuditActions() {
    }

    public static final String REFUND_APPROVED = "REFUND_APPROVED";
    public static final String REFUND_REJECTED = "REFUND_REJECTED";
    public static final String FEEDBACK_REVIEWED = "FEEDBACK_REVIEWED";

    public static final String ROUTING_RULE_CREATED = "ROUTING_RULE_CREATED";
    public static final String ROUTING_RULE_TOGGLED = "ROUTING_RULE_TOGGLED";

    /** 没有真人操作时的操作人占位值。与「匿名」区别开：它说明是系统动作，不是缺了操作人。 */
    public static final String SYSTEM_ACTOR = "system";
}