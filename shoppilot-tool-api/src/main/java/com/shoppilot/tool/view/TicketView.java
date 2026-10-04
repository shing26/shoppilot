package com.shoppilot.tool.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** 人工工单（ADR 0009）。transcript 只在本租户内可见。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketView(
        String id,
        String tenantId,
        String customerId,
        String reason,
        String userQuery,
        String status,
        String priority,
        Instant createdAt,
        // 以下五格是 round23 票 69 / ADR 0055 的统一工单字段，追加在尾部以免改动既有反序列化契约。
        /** 工单来源：DEGRADE / FEEDBACK_REVIEW / REFUND_APPROVAL / CHANNEL_RECEIPT。 */
        String source,
        /** 分派到的队列；null = 尚未分派。 */
        String queue,
        /** 领取人；null = 无人领取。 */
        String assignee,
        /** SLA 截止时间。 */
        Instant slaDeadline,
        /** 有上游记录时指回上游 id 的 JSON；自包含来源为 null。 */
        String payload,
        /** 超时打戳时刻（票 70）；非空即代表已被观测到超时，但工单状态不变。 */
        Instant escalatedAt,
        // 以下两格是 round26 票 86 / ADR 0059 的结果回流前置，追加在尾部以免改动既有反序列化契约。
        /** 买家当初问的渠道（web/app/miniapp/webhook/email）；null = 这张单没有渠道。 */
        String channel,
        /** 投递目标（邮箱或 webhook 回调地址）；null = 无处可投。**它不是身份，也不是隔离依据。 */
        String contact) {

    /** 票 86 之前的十三参构造，落到「无渠道、无目标」。 */
    public TicketView(String id, String tenantId, String customerId, String reason, String userQuery, String status,
                      String priority, Instant createdAt, String source, String queue, String assignee,
                      Instant slaDeadline, String payload, Instant escalatedAt) {
        this(id, tenantId, customerId, reason, userQuery, status, priority, createdAt, source, queue, assignee,
                slaDeadline, payload, escalatedAt, null, null);
    }
}
