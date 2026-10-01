package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.Ticket;
import com.shoppilot.bizmock.domain.TicketSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, String> {

    List<Ticket> findAllByOrderByCreatedAtDesc();

    /**
     * 按来源计数（round23 票 69）。分流规则表（票 70）以这张表为分母，
     * 所以「降级工单有多少」必须能单独数出来——否则新增两种来源后，
     * 「降级 9 种」那个公开口径就分母不保了。
     */
    long countBySource(String source);

    default long countBySource(TicketSource source) {
        return countBySource(source.name());
    }
}
