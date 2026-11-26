package com.shoppilot.commerce;

/**
 * 订单的资金轴。与履约轴分开，避免把“已发货”和“已付款”压成一个枚举。
 */
public enum FinancialStatus {
    PENDING,
    AUTHORIZED,
    PARTIALLY_PAID,
    PAID,
    PARTIALLY_REFUNDED,
    REFUNDED,
    VOIDED,
    EXPIRED,
    UNKNOWN;

    /** 只有已捕获的资金才谈得上退款。 */
    public boolean isCaptured() {
        return this == PARTIALLY_PAID || this == PAID || this == PARTIALLY_REFUNDED;
    }
}
