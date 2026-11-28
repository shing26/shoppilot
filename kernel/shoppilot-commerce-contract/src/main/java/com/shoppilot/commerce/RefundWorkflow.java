package com.shoppilot.commerce;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 退款工作流：本地审批账本 + 商业系统提交 + 结算对账。
 *
 * <p>关键顺序：先落 {@link RefundStatus#REQUESTED}，人工放行后落
 * {@link RefundStatus#APPROVED}，再调 {@link CommerceGateway#createRefund}，
 * 成功才落 {@link RefundStatus#SUBMITTED}。这样“审批”与“提交”不会混成一次重试。
 */
public final class RefundWorkflow {

    private final CommerceGateway commerce;
    private final RefundApprovalStore store;
    private final PolicyProvider policies;
    private final Clock clock;
    private final IdGenerator ids;

    public RefundWorkflow(
            CommerceGateway commerce,
            RefundApprovalStore store,
            PolicyProvider policies,
            Clock clock,
            IdGenerator ids) {
        this.commerce = Objects.requireNonNull(commerce, "commerce");
        this.store = Objects.requireNonNull(store, "store");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public RefundWorkflow(CommerceGateway commerce, RefundApprovalStore store, PolicyProvider policies) {
        this(commerce, store, policies, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    public CommerceResult<RefundRequest> request(TenantId tenant, RefundIntent intent, IdempotencyKey key) {
        var replay = store.findByIdempotencyKey(tenant, key);
        if (replay.isPresent()) {
            return replay.get().intent().equals(intent)
                    ? CommerceResult.ok(replay.get())
                    : CommerceResult.err(CommerceError.INVALID_REQUEST,
                            "idempotency key already used for a different refund intent");
        }

        var orderResult = commerce.getOrder(tenant, intent.order());
        if (!orderResult.isOk()) {
            return CommerceResults.copyErr(orderResult);
        }
        var order = CommerceResults.unwrap(orderResult);
        var now = clock.instant();
        var policy = policies.policyFor(tenant, order);
        if (!policy.allowed()) {
            return CommerceResult.err(CommerceError.STATE_NOT_ALLOWED, policy.denyReason());
        }
        if (!order.refundable()) {
            return CommerceResult.err(CommerceError.STATE_NOT_ALLOWED,
                    "order is not in a refundable state");
        }
        if (!policy.withinWindow(order.paidAt(), now)) {
            return CommerceResult.err(CommerceError.STATE_NOT_ALLOWED,
                    "refund window has expired");
        }
        if (store.findOpenByIntent(tenant, intent).isPresent()) {
            return CommerceResult.err(CommerceError.CONFLICT,
                    "an open refund request already exists for this intent");
        }

        var quoteResult = commerce.quoteRefund(tenant, intent);
        if (!quoteResult.isOk()) {
            return CommerceResults.copyErr(quoteResult);
        }

        var request = new RefundRequest(
                ids.newId(),
                tenant,
                intent,
                RefundStatus.REQUESTED,
                key,
                "",
                "",
                now,
                now);
        store.create(request);
        return CommerceResult.ok(request);
    }

    public CommerceResult<RefundRequest> approve(TenantId tenant, String requestId) {
        var found = store.find(tenant, requestId);
        if (found.isEmpty()) {
            return CommerceResult.err(CommerceError.NOT_FOUND, "refund request not found");
        }
        var request = found.get();

        final RefundRequest approved;
        if (request.status() == RefundStatus.REQUESTED) {
            approved = request.transitionTo(RefundStatus.APPROVED, clock.instant(), "", "");
            store.update(approved);
        } else if (request.status() == RefundStatus.APPROVED) {
            approved = request;
        } else {
            return CommerceResult.err(CommerceError.STATE_NOT_ALLOWED,
                    "only REQUESTED or APPROVED can be approved, current=" + request.status());
        }

        var receiptResult = commerce.createRefund(tenant, approved.intent(), approved.idempotencyKey());
        if (!receiptResult.isOk()) {
            var error = (CommerceResult.Err<?>) receiptResult;
            if (!error.error().retryable()) {
                var failed = approved.transitionTo(
                        RefundStatus.FAILED, clock.instant(), "", error.message());
                store.update(failed);
            }
            return CommerceResults.copyErr(receiptResult);
        }

        var receipt = CommerceResults.unwrap(receiptResult);
        var submitted = approved.transitionTo(
                RefundStatus.SUBMITTED, clock.instant(), receipt.commerceRefundId(), "");
        store.update(submitted);

        if (receipt.status() == RefundStatus.SETTLED) {
            var settled = submitted.transitionTo(
                    RefundStatus.SETTLED, clock.instant(), receipt.commerceRefundId(), "");
            store.update(settled);
            return CommerceResult.ok(settled);
        }
        return CommerceResult.ok(submitted);
    }

    public CommerceResult<RefundRequest> reject(TenantId tenant, String requestId, String reason) {
        var found = store.find(tenant, requestId);
        if (found.isEmpty()) {
            return CommerceResult.err(CommerceError.NOT_FOUND, "refund request not found");
        }
        var request = found.get();
        if (request.status() != RefundStatus.REQUESTED) {
            return CommerceResult.err(CommerceError.STATE_NOT_ALLOWED,
                    "only REQUESTED can be rejected, current=" + request.status());
        }
        var rejected = request.transitionTo(RefundStatus.REJECTED, clock.instant(), "", reason);
        store.update(rejected);
        return CommerceResult.ok(rejected);
    }

    public CommerceResult<RefundRequest> refresh(TenantId tenant, String requestId) {
        var found = store.find(tenant, requestId);
        if (found.isEmpty()) {
            return CommerceResult.err(CommerceError.NOT_FOUND, "refund request not found");
        }
        var request = found.get();
        if (request.status() != RefundStatus.SUBMITTED) {
            return CommerceResult.ok(request);
        }
        if (!request.hasCommerceRefund()) {
            return CommerceResult.err(CommerceError.INTERNAL,
                    "SUBMITTED request has no commerce refund id");
        }

        var statusResult = commerce.getRefundStatus(tenant, request.commerceRefundId());
        if (!statusResult.isOk()) {
            return CommerceResults.copyErr(statusResult);
        }
        var status = CommerceResults.unwrap(statusResult);
        return switch (status) {
            case SETTLED -> {
                var settled = request.transitionTo(
                        RefundStatus.SETTLED, clock.instant(), request.commerceRefundId(), "");
                store.update(settled);
                yield CommerceResult.ok(settled);
            }
            case FAILED -> {
                var failed = request.transitionTo(
                        RefundStatus.FAILED, clock.instant(), request.commerceRefundId(), "settlement failed");
                store.update(failed);
                yield CommerceResult.ok(failed);
            }
            case SUBMITTED -> CommerceResult.ok(request);
            default -> CommerceResult.err(CommerceError.INTERNAL,
                    "unexpected commerce refund status: " + status);
        };
    }
}
