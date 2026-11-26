package com.shoppilot.commerce;

/**
 * 按租户/订单给出退款政策。正式实现可以读店铺配置或退货政策服务。
 */
@FunctionalInterface
public interface PolicyProvider {

    RefundPolicy policyFor(TenantId tenant, OrderSnapshot order);

    static PolicyProvider allowAll(int windowDays) {
        return (tenant, order) -> RefundPolicy.allow(windowDays);
    }
}
