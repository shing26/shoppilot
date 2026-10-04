package com.shoppilot.tool.audit;

/**
 * 事件 topic 名（ADR 0054）。
 *
 * <p>round23 立的规矩：<b>只给真正有生产者的 topic 造代码</b>，为没有消费者的 topic 造生产端
 * 与消费端是本仓明禁的 speculative generality（所有者裁定 C，2026-10-01）。
 * round26 票 87 给 {@link #CHANNEL_OUTBOUND} 配上了第一生产者（工单结单），
 * 票 88 配上消费端（网关投递）——**它是三条里第一条真正落地的**。
 *
 * <p>仍未落地的：
 * <ul>
 *   <li>{@link #TICKET_CREATED} —— 工单创建事件。工单服务自己就是它唯一的读者
 *       （表就在它那儿），走事件绕一圈没有消费者。</li>
 * </ul>
 */
public final class AuditTopics {

    private AuditTopics() {
    }

    /** 审计流。生产端：本仓已有的退款放行/驳回、反馈复核完成、分流规则变更。 */
    public static final String AUDIT = "shoppilot:audit";

    /** 工单创建事件——**仍然只有契约**（见类注释）。 */
    public static final String TICKET_CREATED = "shoppilot:ticket-created";

    /** 渠道出站流。生产端：工单服务在 resolve 时发（round26 票 87）。消费端：网关投递（票 88）。 */
    public static final String CHANNEL_OUTBOUND = "shoppilot:channel-outbound";

    /** 审计流的消费组；处理方自己再起名字。 */
    public static final String AUDIT_GROUP = "shoppilot-audit";

    /** 审计流的长度上限：审计是给「近期」查的，无界增长会把 Redis 的内存吃光。 */
    public static final int AUDIT_MAXLEN = 10_000;
}