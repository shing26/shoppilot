package com.shoppilot.tool.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundView(
        String refundId,
        String orderNo,
        long amountFen,
        String status,
        Instant createdAt) {
}
