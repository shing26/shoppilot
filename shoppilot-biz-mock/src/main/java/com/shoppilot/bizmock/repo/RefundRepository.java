package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByOrderIdAndIdempotencyToken(String orderId, String idempotencyToken);

    long countByOrderId(String orderId);

    /** 审核队列（ADR 0047）：租户由实体上的 {@code @TenantId} 自动拼接，这里只按状态取。 */
    List<Refund> findByStatusOrderByCreatedAtAsc(String status);

    /** 订单最近一笔退款（票 60）：订单详情要带上它的审核态，供买家读回。 */
    Optional<Refund> findFirstByOrderIdOrderByCreatedAtDesc(String orderId);
}
