package com.shoppilot.commerce;

import java.util.List;
import java.util.Objects;

/**
 * 退款报价。金额由行项目与运费推导，调用方不能改。
 */
public record RefundQuote(
        OrderRef order,
        List<QuotedLine> lines,
        Money shipping,
        Money amount,
        Money remainingAfter) {

    public RefundQuote {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(shipping, "shipping");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(remainingAfter, "remainingAfter");
        lines = List.copyOf(lines);

        Money computed = lines.stream()
                .map(QuotedLine::amount)
                .reduce(Money.zero(amount.currency()), Money::plus)
                .plus(shipping);
        if (!computed.equals(amount)) {
            throw new IllegalArgumentException("quote amount does not equal lines + shipping");
        }
        amount.requireSameCurrency(remainingAfter);
    }

    public record QuotedLine(String lineItemId, int quantity, Money amount) {

        public QuotedLine {
            if (lineItemId == null || lineItemId.isBlank()) {
                throw new IllegalArgumentException("lineItemId must not be blank");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be > 0");
            }
            Objects.requireNonNull(amount, "amount");
        }
    }
}
