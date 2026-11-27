package com.shoppilot.commerce;

/**
 * 订单引用。内核不规定它是订单号还是 GID；由适配器决定解析方式。
 */
public record OrderRef(String value) {

    public OrderRef {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("order ref must not be blank");
        }
        value = value.trim();
    }
}
