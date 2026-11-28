package com.shoppilot.commerce;

/**
 * 商业系统（Shopify 或其他电商）的退款端口。
 *
 * <p>实现必须满足：
 * <ul>
 *   <li>租户隔离：跨租户的订单/退款一律 {@link CommerceError#NOT_FOUND}，不确认存在；</li>
 *   <li>报价只读：{@link #quoteRefund} 不改变任何状态；</li>
 *   <li>幂等：同一 key + 同一 intent 重放同一 receipt；同一 key + 不同 intent 返回
 *       {@link CommerceError#INVALID_REQUEST}；</li>
 *   <li>不虚报到账：{@link #createRefund} 可以返回 {@link RefundStatus#SUBMITTED}
 *       或 {@link RefundStatus#SETTLED}；只有响应里出现支付通道的 {@code SUCCESS}
 *       终态时才允许返回 {@link RefundStatus#SETTLED}，仅有“退款单已创建”不算到账。</li>
 * </ul>
 */
public interface CommerceGateway {

    CommerceResult<OrderSnapshot> getOrder(TenantId tenant, OrderRef order);

    CommerceResult<RefundQuote> quoteRefund(TenantId tenant, RefundIntent intent);

    CommerceResult<RefundReceipt> createRefund(TenantId tenant, RefundIntent intent, IdempotencyKey key);

    CommerceResult<RefundStatus> getRefundStatus(TenantId tenant, String commerceRefundId);
}
