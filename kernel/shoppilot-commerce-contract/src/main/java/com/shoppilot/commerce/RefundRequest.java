package com.shoppilot.commerce;

import java.time.Instant;
import java.util.Objects;

/**
 * 本地审批账本里的一条退款请求（聚合根）。
 *
 * <p>它不是商业系统的退款单：商业系统的退款单只在 {@link RefundStatus#APPROVED}
 * 之后由 {@link CommerceGateway#createRefund} 创建。
 */
public record RefundRequest(
        String id,
        TenantId tenant,
        RefundIntent intent,
        RefundStatus status,
        IdempotencyKey idempotencyKey,
        String commerceRefundId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public RefundRequest {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("request id must not be blank");
        }
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        commerceRefundId = commerceRefundId == null ? "" : commerceRefundId.trim();
        failureReason = failureReason == null ? "" : failureReason.trim();
    }

    public RefundRequest transitionTo(
            RefundStatus next,
            Instant now,
            String commerceRefundId,
            String failureReason) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("illegal refund transition: " + status + " -> " + next);
        }
        if (next == RefundStatus.SUBMITTED && (commerceRefundId == null || commerceRefundId.isBlank())) {
            throw new IllegalArgumentException("SUBMITTED requires a commerce refund id");
        }
        return new RefundRequest(
                id,
                tenant,
                intent,
                next,
                idempotencyKey,
                commerceRefundId,
                failureReason,
                createdAt,
                Objects.requireNonNull(now, "now"));
    }

    public boolean hasCommerceRefund() {
        return !commerceRefundId.isBlank();
    }
}
