package com.shoppilot.tool.audit;

/**
 * 事件 topic 名（ADR 0054）。**只有 {@link #AUDIT} 本轮有实现**——另两条是留给渠道出站与工单事件的
 * 契约占位，它们目前**没有消费者**；为没有消费者的 topic 造生产端与消费端是本仓明禁的
 * speculative generality（所有者裁定 C，2026-10-01）。
 *
 * <p>真要落地时的触发条件写在这里，免得下一个人以为「契约在就等于功能在」：
 * <ul>
 *   <li>{@link #TICKET_CREATED} —— 工单服务（票 72）拆出后有真实下游时；</li>
 *   <li>{@link #CHANNEL_OUTBOUND} —— 渠道出站落地时。</li>
 * </ul>
 */
public final class AuditTopics {

    private AuditTopics() {
    }

    /** 审计流。生产端：本仓已有的退款放行/驳回、反馈复核完成、分流规则变更。 */
    public static final String AUDIT = "shoppilot:audit";

    /** 工单创建事件——**本轮只有契约，没有实现**（见类注释）。 */
    public static final String TICKET_CREATED = "shoppilot:ticket-created";

    /** 渠道出站事件——**本轮只有契约，没有实现**（见类注释）。 */
    public static final String CHANNEL_OUTBOUND = "shoppilot:channel-outbound";

    /** 审计流的消费组；处理方自己再起名字。 */
    public static final String AUDIT_GROUP = "shoppilot-audit";

    /** 审计流的长度上限：审计是给「近期」查的，无界增长会把 Redis 的内存吃光。 */
    public static final int AUDIT_MAXLEN = 10_000;
}