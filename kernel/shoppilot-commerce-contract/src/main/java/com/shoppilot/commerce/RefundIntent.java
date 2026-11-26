package com.shoppilot.commerce;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次退款的业务意图：退哪些行项目、各几件、是否退运费、原因。
 *
 * <p>刻意没有金额字段：金额由 {@code quoteRefund} 从订单快照推导，
 * 防止模型或调用方凭空给一个数。
 */
public record RefundIntent(
        OrderRef order,
        List<RefundLine> lines,
        boolean refundShipping,
        String reason) {

    public RefundIntent {
        Objects.requireNonNull(order, "order");
        lines = List.copyOf(lines);
        if (lines.isEmpty() && !refundShipping) {
            throw new IllegalArgumentException("refund intent must contain a line item or request shipping");
        }
        Set<String> seen = new HashSet<>();
        for (RefundLine line : lines) {
            if (!seen.add(line.lineItemId())) {
                throw new IllegalArgumentException("duplicate line item in refund intent: " + line.lineItemId());
            }
        }
        reason = reason == null ? "" : reason.trim();
    }
}
