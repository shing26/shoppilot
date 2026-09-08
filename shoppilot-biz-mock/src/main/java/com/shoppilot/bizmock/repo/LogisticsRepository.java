package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.LogisticsNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LogisticsRepository extends JpaRepository<LogisticsNode, Long> {

    List<LogisticsNode> findByOrderIdOrderBySeqAsc(String orderId);
}
