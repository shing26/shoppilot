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
        String payload) {
}
