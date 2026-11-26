package com.shoppilot.commerce;

/**
 * 一次退款提交的幂等键。生成后必须先落库，再调用外部系统。
 */
public record IdempotencyKey(String value) {

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
        value = value.trim();
        if (value.length() > 255) {
            throw new IllegalArgumentException("idempotency key must be <= 255 chars");
        }
    }
}
