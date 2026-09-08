package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 归属双条件（ADR 0004）：租户由实体上的 {@code @TenantId} 自动拼接，
 * 这里再叠加买家条件。刻意不提供任何"忽略租户按 id 查"的方法。
 */
public interface OrderRepository extends JpaRepository<Order, String> {

    Optional<Order> findByIdAndCustomerId(String id, String customerId);
}
