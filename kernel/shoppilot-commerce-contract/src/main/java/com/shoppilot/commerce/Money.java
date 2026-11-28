package com.shoppilot.commerce;

import java.util.Locale;
import java.util.Objects;

/**
 * 金额的最小单位 + ISO-4217 币种。
 *
 * <p>端口上不出现 {@code double}，也不出现裸 {@code long}：Shopify 返回的是
 * 十进制字符串 + 双币种，适配器负责在边界上转换；内核只做同币种的整数运算。
 */
public record Money(long minorUnits, String currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
        currency = currency.trim().toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code: " + currency);
        }
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public static Money of(long minorUnits, String currency) {
        return new Money(minorUnits, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money multiply(int factor) {
        if (factor < 0) {
            throw new IllegalArgumentException("factor must be >= 0");
        }
        return new Money(Math.multiplyExact(minorUnits, factor), currency);
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }
}
