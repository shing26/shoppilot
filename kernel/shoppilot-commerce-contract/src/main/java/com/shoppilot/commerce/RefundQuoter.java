package com.shoppilot.commerce;

import java.util.ArrayList;
import java.util.List;

/**
 * 从订单快照推导退款报价。纯函数，没有 IO，也没有“调用方给金额”的入口。
 */
public final class RefundQuoter {

    private RefundQuoter() {
    }

    public static CommerceResult<RefundQuote> quote(OrderSnapshot order, RefundIntent intent) {
        if (!order.ref().equals(intent.order())) {
            return CommerceResult.err(CommerceError.INVALID_REQUEST,
                    "refund intent order does not match the order snapshot");
        }
        if (!order.refundable()) {
            return CommerceResult.err(CommerceError.STATE_NOT_ALLOWED, "order is not refundable");
        }

        List<RefundQuote.QuotedLine> quoted = new ArrayList<>();
        Money lineTotal = Money.zero(order.total().currency());
        for (RefundLine line : intent.lines()) {
            var item = order.lineItem(line.lineItemId()).orElse(null);
            if (item == null) {
                return CommerceResult.err(CommerceError.INVALID_REQUEST,
                        "unknown line item: " + line.lineItemId());
            }
            if (line.quantity() > item.refundableQuantity()) {
                return CommerceResult.err(CommerceError.STATE_NOT_ALLOWED,
                        "quantity exceeds refundable amount for " + line.lineItemId());
            }
            Money amount = item.unitPrice().multiply(line.quantity());
            quoted.add(new RefundQuote.QuotedLine(line.lineItemId(), line.quantity(), amount));
            lineTotal = lineTotal.plus(amount);
        }

        Money shipping;
        if (intent.refundShipping()) {
            if (order.shippingRefunded()) {
                return CommerceResult.err(CommerceError.STATE_NOT_ALLOWED, "shipping already refunded");
            }
            shipping = order.shipping();
        } else {
            shipping = Money.zero(order.total().currency());
        }

        Money amount = lineTotal.plus(shipping);
        if (amount.isZero()) {
            return CommerceResult.err(CommerceError.INVALID_REQUEST, "refund amount is zero");
        }
        if (amount.compareTo(order.remainingRefundable()) > 0) {
            return CommerceResult.err(CommerceError.STATE_NOT_ALLOWED,
                    "refund exceeds remaining refundable amount");
        }
        return CommerceResult.ok(new RefundQuote(
                order.ref(), quoted, shipping, amount, order.remainingRefundable().minus(amount)));
    }
}
