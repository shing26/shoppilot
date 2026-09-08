package com.shoppilot.tool.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * 订单视图。注意：不含 tenantId 与 customerId —— 归属校验在服务端完成，
 * 不把身份字段带进给模型的上下文，避免模型复述时泄露。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderView(
        String orderNo,
        long amountFen,
        OrderStatus status,
        String categoryName,
        List<String> serviceFlags,
        AddressView address,
        Instant createdAt,
        Instant paidAt,
        Instant shippedAt) {

    public String amountYuan() {
        return String.format("%.2f", amountFen / 100.0d);
    }
}
