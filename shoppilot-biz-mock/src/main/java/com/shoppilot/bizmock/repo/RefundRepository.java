package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByOrderIdAndIdempotencyToken(String orderId, String idempotencyToken);

    long countByOrderId(String orderId);
}
