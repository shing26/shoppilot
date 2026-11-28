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
        Instant shippedAt,
        /**
         * 该订单当前退款申请的审核态（票 60）。无在办退款/无退款时为 null。
         * 它补齐的正是 {@link OrderStatus#REFUNDING} 那格"待审与已放行同形"的缺口。
         */
        RefundReviewState refundReview,
        /**
         * 退款到账边界（票 235）。无退款为 null；有退款时固定为
         * {@link PayoutBoundary#PAYMENT_CHANNEL}，与自由文本 {@code message} 并存。
         */
        PayoutBoundary payoutBoundary) {

    public String amountYuan() {
        return String.format("%.2f", amountFen / 100.0d);
    }
}
