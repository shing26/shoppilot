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
        Instant createdAt) {
}
