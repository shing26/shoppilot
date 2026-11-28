package com.shoppilot.commerce;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次订单读取的不可变快照。金额与数量都来自商业系统，不由调用方猜。
 */
public record OrderSnapshot(
        OrderRef ref,
        String commerceOrderId,
        Money total,
        Money refunded,
        Money shipping,
        boolean shippingRefunded,
        FinancialStatus financial,
        FulfillmentStatus fulfillment,
        List<LineItem> lineItems,
        Instant createdAt,
        Instant paidAt) {

    public OrderSnapshot {
        Objects.requireNonNull(ref, "ref");
        if (commerceOrderId == null || commerceOrderId.isBlank()) {
            throw new IllegalArgumentException("commerceOrderId must not be blank");
        }
        commerceOrderId = commerceOrderId.trim();
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(refunded, "refunded");
        Objects.requireNonNull(shipping, "shipping");
        Objects.requireNonNull(financial, "financial");
        Objects.requireNonNull(fulfillment, "fulfillment");
        Objects.requireNonNull(createdAt, "createdAt");
        lineItems = List.copyOf(lineItems);
        if (total.isNegative() || refunded.isNegative() || shipping.isNegative()) {
            throw new IllegalArgumentException("order amounts must not be negative");
        }
        total.requireSameCurrency(refunded);
        total.requireSameCurrency(shipping);
        for (LineItem item : lineItems) {
            total.requireSameCurrency(item.unitPrice());
        }
    }

    public Money remainingRefundable() {
        return total.minus(refunded);
    }

    public boolean refundable() {
        return financial.isCaptured() && remainingRefundable().isPositive();
    }

    public Optional<LineItem> lineItem(String id) {
        return lineItems.stream().filter(item -> item.id().equals(id)).findFirst();
    }

    /**
     * 商业系统受理退款后的新快照。参考实现用它表达“已占用可退额度”。
     */
    public OrderSnapshot withRefundApplied(Money amount, List<RefundLine> lines, boolean refundShipping) {
        total.requireSameCurrency(amount);
        List<LineItem> updated = lineItems.stream()
                .map(item -> {
                    int delta = lines.stream()
                            .filter(line -> line.lineItemId().equals(item.id()))
                            .mapToInt(RefundLine::quantity)
                            .sum();
                    return delta == 0 ? item : item.withRefundedQuantity(item.refundedQuantity() + delta);
                })
                .toList();
        return new OrderSnapshot(
                ref,
                commerceOrderId,
                total,
                refunded.plus(amount),
                shipping,
                shippingRefunded || refundShipping,
                financial,
                fulfillment,
                updated,
                createdAt,
                paidAt);
    }
}
