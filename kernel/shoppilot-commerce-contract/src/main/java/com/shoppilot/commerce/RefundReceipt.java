package com.shoppilot.commerce;

import java.time.Instant;
import java.util.Objects;

/**
 * 商业系统对一次退款提交的回执。
 *
 * <p>{@code SUBMITTED} 只代表“已发出”，不代表“已到账”；到账看 {@link RefundStatus#SETTLED}。
 */
public record RefundReceipt(
        String commerceRefundId,
        RefundStatus status,
        Money amount,
        IdempotencyKey key,
        Instant submittedAt) {

    public RefundReceipt {
        if (commerceRefundId == null || commerceRefundId.isBlank()) {
            throw new IllegalArgumentException("commerceRefundId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(submittedAt, "submittedAt");
        if (status != RefundStatus.SUBMITTED && status != RefundStatus.SETTLED) {
            throw new IllegalArgumentException("receipt status must be SUBMITTED or SETTLED");
        }
    }
}
