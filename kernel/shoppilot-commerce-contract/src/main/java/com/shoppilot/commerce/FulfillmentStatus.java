package com.shoppilot.commerce;

/**
 * 订单的履约轴。地址修改、退货入库等判断应该看它，而不是看资金轴。
 */
public enum FulfillmentStatus {
    UNFULFILLED,
    PARTIALLY_FULFILLED,
    FULFILLED,
    RESTOCKED,
    IN_PROGRESS,
    ON_HOLD,
    SCHEDULED,
    UNKNOWN;

    public boolean hasUnfulfilledItems() {
        return this == UNFULFILLED || this == PARTIALLY_FULFILLED;
    }
}
