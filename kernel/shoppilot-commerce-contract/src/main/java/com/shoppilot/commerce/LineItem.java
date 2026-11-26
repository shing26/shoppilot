package com.shoppilot.commerce;

import java.util.Objects;

/**
 * 订单行项目。退款按“行项目 + 数量”表达，不按一个裸金额表达。
 */
public record LineItem(
        String id,
        String sku,
        String title,
        int quantity,
        int refundedQuantity,
        Money unitPrice) {

    public LineItem {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("line item id must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (refundedQuantity < 0 || refundedQuantity > quantity) {
            throw new IllegalArgumentException("refundedQuantity must be within [0, quantity]");
        }
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (unitPrice.isNegative()) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
        sku = sku == null ? "" : sku;
        title = title == null ? "" : title;
    }

    public int refundableQuantity() {
        return quantity - refundedQuantity;
    }

    public Money refundableAmount() {
        return unitPrice.multiply(refundableQuantity());
    }

    public LineItem withRefundedQuantity(int newRefundedQuantity) {
        return new LineItem(id, sku, title, quantity, newRefundedQuantity, unitPrice);
    }
}
