package com.shoppilot.commerce.fake;

import com.shoppilot.commerce.IdempotencyKey;
import com.shoppilot.commerce.RefundApprovalStore;
import com.shoppilot.commerce.RefundIntent;
import com.shoppilot.commerce.RefundRequest;
import com.shoppilot.commerce.RefundStatus;
import com.shoppilot.commerce.TenantId;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 参考审批账本。只用于测试；生产实现需要持久化、CAS 与 outbox。
 */
public final class InMemoryRefundApprovalStore implements RefundApprovalStore {

    private final Map<String, RefundRequest> requests = new ConcurrentHashMap<>();

    private static String key(TenantId tenant, String requestId) {
        return tenant.value() + "|" + requestId;
    }

    @Override
    public RefundRequest create(RefundRequest request) {
        var prior = requests.putIfAbsent(key(request.tenant(), request.id()), request);
        if (prior != null) {
            throw new IllegalStateException("duplicate refund request id: " + request.id());
        }
        return request;
    }

    @Override
    public Optional<RefundRequest> find(TenantId tenant, String requestId) {
        return Optional.ofNullable(requests.get(key(tenant, requestId)));
    }

    @Override
    public Optional<RefundRequest> findByIdempotencyKey(TenantId tenant, IdempotencyKey idempotencyKey) {
        return requests.values().stream()
                .filter(request -> request.tenant().equals(tenant))
                .filter(request -> request.idempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    @Override
    public Optional<RefundRequest> findOpenByIntent(TenantId tenant, RefundIntent intent) {
        return requests.values().stream()
                .filter(request -> request.tenant().equals(tenant))
                .filter(request -> request.intent().equals(intent))
                .filter(request -> !request.status().isTerminal())
                .findFirst();
    }

    @Override
    public RefundRequest update(RefundRequest request) {
        requests.put(key(request.tenant(), request.id()), request);
        return request;
    }

    @Override
    public List<RefundRequest> pending(TenantId tenant) {
        return requests.values().stream()
                .filter(request -> request.tenant().equals(tenant))
                .filter(request -> request.status() == RefundStatus.REQUESTED)
                .sorted(Comparator.comparing(RefundRequest::createdAt))
                .toList();
    }
}
