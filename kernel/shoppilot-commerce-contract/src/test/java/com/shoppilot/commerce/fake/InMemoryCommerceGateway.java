package com.shoppilot.commerce.fake;

import com.shoppilot.commerce.CommerceError;
import com.shoppilot.commerce.CommerceGateway;
import com.shoppilot.commerce.CommerceResult;
import com.shoppilot.commerce.CommerceResults;
import com.shoppilot.commerce.IdempotencyKey;
import com.shoppilot.commerce.Money;
import com.shoppilot.commerce.OrderRef;
import com.shoppilot.commerce.OrderSnapshot;
import com.shoppilot.commerce.RefundIntent;
import com.shoppilot.commerce.RefundQuote;
import com.shoppilot.commerce.RefundReceipt;
import com.shoppilot.commerce.RefundQuoter;
import com.shoppilot.commerce.RefundStatus;
import com.shoppilot.commerce.TenantId;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 参考实现：只用于让 TCK 在 0 外部依赖下跑起来，不是生产适配器。
 *
 * <p>已知边界：结算失败不回滚订单快照；并发不保证；金额只支持单币种。
 */
public final class InMemoryCommerceGateway implements CommerceGateway {

    private record RefundRecord(
            String id,
            OrderRef order,
            RefundIntent intent,
            Money amount,
            IdempotencyKey key,
            RefundStatus status,
            Instant submittedAt) {
    }

    private final Map<String, OrderSnapshot> orders = new ConcurrentHashMap<>();
    private final Map<String, RefundRecord> refunds = new ConcurrentHashMap<>();
    private final Map<String, RefundRecord> refundsByKey = new ConcurrentHashMap<>();
    private final AtomicInteger refundSequence = new AtomicInteger();
    private final AtomicInteger createRefundCalls = new AtomicInteger();
    private volatile CommerceError nextCreateError;

    private static String orderKey(TenantId tenant, OrderRef order) {
        return tenant.value() + "|" + order.value();
    }

    private static String keyKey(TenantId tenant, IdempotencyKey key) {
        return tenant.value() + "|" + key.value();
    }

    private static String refundKey(TenantId tenant, String refundId) {
        return tenant.value() + "|" + refundId;
    }

    public void seedOrder(TenantId tenant, OrderSnapshot order) {
        orders.put(orderKey(tenant, order.ref()), order);
    }

    public int createRefundCalls() {
        return createRefundCalls.get();
    }

    public void failNextCreate(CommerceError error) {
        this.nextCreateError = error;
    }

    @Override
    public CommerceResult<OrderSnapshot> getOrder(TenantId tenant, OrderRef order) {
        var found = orders.get(orderKey(tenant, order));
        return found == null
                ? CommerceResult.err(CommerceError.NOT_FOUND, "order not found in tenant")
                : CommerceResult.ok(found);
    }

    @Override
    public CommerceResult<RefundQuote> quoteRefund(TenantId tenant, RefundIntent intent) {
        var orderResult = getOrder(tenant, intent.order());
        if (!orderResult.isOk()) {
            return CommerceResults.copyErr(orderResult);
        }
        return RefundQuoter.quote(CommerceResults.unwrap(orderResult), intent);
    }

    @Override
    public CommerceResult<RefundReceipt> createRefund(
            TenantId tenant, RefundIntent intent, IdempotencyKey key) {
        createRefundCalls.incrementAndGet();

        var injected = nextCreateError;
        if (injected != null) {
            nextCreateError = null;
            return CommerceResult.err(injected, "injected failure");
        }

        var replay = refundsByKey.get(keyKey(tenant, key));
        if (replay != null) {
            return replay.intent().equals(intent)
                    ? CommerceResult.ok(receipt(replay))
                    : CommerceResult.err(CommerceError.INVALID_REQUEST,
                            "idempotency key reused with a different intent");
        }

        var quoteResult = quoteRefund(tenant, intent);
        if (!quoteResult.isOk()) {
            return CommerceResults.copyErr(quoteResult);
        }
        var quote = CommerceResults.unwrap(quoteResult);
        var orderResult = getOrder(tenant, intent.order());
        if (!orderResult.isOk()) {
            return CommerceResults.copyErr(orderResult);
        }
        var order = CommerceResults.unwrap(orderResult);

        var id = "rf_" + refundSequence.incrementAndGet();
        var record = new RefundRecord(
                id, intent.order(), intent, quote.amount(), key, RefundStatus.SUBMITTED, Instant.now());
        refunds.put(refundKey(tenant, id), record);
        refundsByKey.put(keyKey(tenant, key), record);
        orders.put(orderKey(tenant, intent.order()),
                order.withRefundApplied(quote.amount(), intent.lines(), intent.refundShipping()));
        return CommerceResult.ok(receipt(record));
    }

    @Override
    public CommerceResult<RefundStatus> getRefundStatus(TenantId tenant, String commerceRefundId) {
        var record = refunds.get(refundKey(tenant, commerceRefundId));
        return record == null
                ? CommerceResult.err(CommerceError.NOT_FOUND, "refund not found in tenant")
                : CommerceResult.ok(record.status());
    }

    public void markSettled(TenantId tenant, String commerceRefundId) {
        transition(tenant, commerceRefundId, RefundStatus.SETTLED);
    }

    public void markFailed(TenantId tenant, String commerceRefundId) {
        transition(tenant, commerceRefundId, RefundStatus.FAILED);
    }

    private void transition(TenantId tenant, String commerceRefundId, RefundStatus next) {
        var mapKey = refundKey(tenant, commerceRefundId);
        var record = refunds.get(mapKey);
        if (record == null) {
            throw new IllegalArgumentException("unknown refund: " + commerceRefundId);
        }
        if (!record.status().canTransitionTo(next)) {
            throw new IllegalStateException("illegal refund transition: " + record.status() + " -> " + next);
        }
        refunds.put(mapKey, new RefundRecord(
                record.id(), record.order(), record.intent(), record.amount(),
                record.key(), next, record.submittedAt()));
    }

    private static RefundReceipt receipt(RefundRecord record) {
        return new RefundReceipt(
                record.id(), record.status(), record.amount(), record.key(), record.submittedAt());
    }
}
