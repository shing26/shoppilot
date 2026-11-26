package com.shoppilot.commerce;

/**
 * 退款意图里的一行：退哪个订单行项目、退几件。
 */
public record RefundLine(String lineItemId, int quantity) {

    public RefundLine {
        if (lineItemId == null || lineItemId.isBlank()) {
            throw new IllegalArgumentException("lineItemId must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
    }
}
