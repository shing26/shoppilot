package com.shoppilot.commerce;

import java.util.List;
import java.util.Optional;

/**
 * 本地审批账本。正式实现需要持久化与 CAS；这里的接口先钉语义。
 *
 * <p>“open” 指 {@link RefundStatus#REQUESTED}、{@link RefundStatus#APPROVED}、
 * {@link RefundStatus#SUBMITTED}：这三种状态下同一 intent 不允许再起一条请求。
 */
public interface RefundApprovalStore {

    RefundRequest create(RefundRequest request);

    Optional<RefundRequest> find(TenantId tenant, String requestId);

    Optional<RefundRequest> findByIdempotencyKey(TenantId tenant, IdempotencyKey key);

    Optional<RefundRequest> findOpenByIntent(TenantId tenant, RefundIntent intent);

    RefundRequest update(RefundRequest request);

    List<RefundRequest> pending(TenantId tenant);
}
