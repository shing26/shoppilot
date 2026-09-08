package com.shoppilot.tool.view;

/** 订单状态机（ticket 03）：CREATED -> PAID -> SHIPPED -> DELIVERED -> COMPLETED，分支 CANCELLED 与 REFUNDING -> REFUNDED。 */
public enum OrderStatus {
    CREATED,
    PAID,
    SHIPPED,
    DELIVERED,
    COMPLETED,
    CANCELLED,
    REFUNDING,
    REFUNDED;

    /** 改地址仅未发货允许。 */
    public boolean addressModifiable() {
        return this == CREATED || this == PAID;
    }

    public boolean refundable() {
        return this == PAID || this == SHIPPED || this == DELIVERED;
    }
}
