package com.shoppilot.tool.audit;

import java.time.Instant;

/**
 * 渠道出站事件——**本轮只有契约，没有生产端也没有消费端**（ADR 0054 / 所有者裁定 C）。
 *
 * <p>形状先定下来是因为它要跨进程（工单服务/网关产出，渠道适配器消费），两边必须编译同一个形状。
 * 但**只有契约不等于功能在**：谁看到 {@link AuditTopics#CHANNEL_OUTBOUND} 都该知道本轮它没实现。
 * 落地触发 = 渠道出站那一轮。
 */
public record ChannelOutboundEvent(
        String eventId,
        int schemaVersion,
        String tenantId,
        /** webhook / email / web 之类的渠道标识。 */
        String channel,
        /** 收件目标（webhook URL 或邮件地址）——出站适配器据此投递。 */
        String target,
        String body,
        Instant occurredAt) {

    public static final int CURRENT_SCHEMA = 1;
}