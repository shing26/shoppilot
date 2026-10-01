package com.shoppilot.tool.audit;

import java.time.Instant;

/**
 * 审计事件（round23 票 71 / ADR 0054、0056）。
 *
 * <p>契约放在共享库里而不是某个服务里：事件的生产者与消费者不共进程（票 72 之后工单服务也发审计），
 * 两边要对同一个形状达成一致，就得有一份**两边都编译得进**的定义。
 *
 * <p>{@code eventId} 是幂等键：投递语义是**至少一次**（ADR 0054），消费端按它去重。
 * 它不是业务 id——同一笔退款被放行两次（重试、并发审核）就是两个不同的事件。
 *
 * <p>刻意不放正文：审计记「谁在什么时候对哪个对象做了什么」，理由与细节留在各自的事务里。
 * 审计表要是能塞进任意负载，它就会变成第二本业务账，而那正是本仓反复在防的事。
 */
public record AuditEvent(
        String eventId,
        int schemaVersion,
        String action,
        String objectType,
        String objectId,
        String tenantId,
        /** 操作人：坐席 id、{@code system} 或运维账号。 */
        String actor,
        /** 一句话说明，不放结构化负载。 */
        String detail,
        Instant occurredAt) {

    public static final int CURRENT_SCHEMA = 1;

    public AuditEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("审计事件必须有 eventId，它是幂等键");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("审计事件必须有 action");
        }
    }
}