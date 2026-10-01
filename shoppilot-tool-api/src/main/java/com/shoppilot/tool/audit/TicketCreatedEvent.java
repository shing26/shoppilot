package com.shoppilot.tool.audit;

import java.time.Instant;

/**
 * 工单创建事件——**本轮只有契约，没有生产端也没有消费端**（ADR 0054 / 所有者裁定 C）。
 *
 * <p>注意它与 {@link AuditEvent} 的分工：审计记「谁做了什么」，工单创建记「系统产出了什么工作项」。
 * 两者不是同一个视角，所以分两条——把工单创建也塞进审计流，审计就会退化成流水账。
 * 落地触发 = 工单服务拆出后出现真实下游（渠道出站、统计看板）。
 */
public record TicketCreatedEvent(
        String eventId,
        int schemaVersion,
        String tenantId,
        String ticketId,
        /** 工单来源，见 {@code TicketSource}。 */
        String source,
        String queue,
        String priority,
        Instant occurredAt) {

    public static final int CURRENT_SCHEMA = 1;
}