package com.shoppilot.commerce;

/**
 * 租户。Shopify 适配器里它就是 shop domain（例如 {@code demo.myshopify.com}）。
 */
public record TenantId(String value) {

    public TenantId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tenant id must not be blank");
        }
        value = value.trim();
    }
}
