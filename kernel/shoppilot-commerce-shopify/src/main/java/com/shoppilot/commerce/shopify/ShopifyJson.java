package com.shoppilot.commerce.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.shoppilot.commerce.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

/**
 * Shopify JSON → 内核类型的边界转换。金额必须走 BigDecimal，不能先转 double。
 */
final class ShopifyJson {

    private ShopifyJson() {
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing instant field: " + field);
        }
        return Instant.parse(value);
    }

    static Instant instantOrNull(JsonNode node, String field) {
        String value = text(node, field);
        return value.isBlank() ? null : Instant.parse(value);
    }

    static Money money(JsonNode moneyBag) {
        JsonNode shopMoney = moneyBag.path("shopMoney");
        if (shopMoney.isMissingNode() || shopMoney.isNull()) {
            throw new IllegalArgumentException("missing shopMoney");
        }
        String amount = shopMoney.path("amount").asText();
        String currency = shopMoney.path("currencyCode").asText();
        return new Money(toMinor(amount, currency), currency);
    }

    static Money moneyOrZero(JsonNode moneyBag, String fallbackCurrency) {
        if (moneyBag == null || moneyBag.isMissingNode() || moneyBag.isNull()
                || moneyBag.path("shopMoney").isMissingNode()) {
            return Money.zero(fallbackCurrency);
        }
        return money(moneyBag);
    }

    static Money sumShipping(JsonNode nodes, String currency) {
        Money total = Money.zero(currency);
        if (nodes == null || !nodes.isArray()) {
            return total;
        }
        for (JsonNode node : nodes) {
            total = total.plus(moneyOrZero(node.path("originalPriceSet"), currency));
        }
        return total;
    }

    static long toMinor(String amount, String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        if (digits < 0) {
            digits = 2;
        }
        return new BigDecimal(amount).movePointRight(digits).longValueExact();
    }

    static String toDecimal(long minorUnits, String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        if (digits < 0) {
            digits = 2;
        }
        return BigDecimal.valueOf(minorUnits).movePointLeft(digits).setScale(digits).toPlainString();
    }
}
