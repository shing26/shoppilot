package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.AuditEventRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRowRepository extends JpaRepository<AuditEventRow, Long> {

    /** 上限由调用方用 Pageable 给：派生查询不接受末尾的 int 参数。 */
    List<AuditEventRow> findByOrderByOccurredAtDesc(Pageable pageable);

    List<AuditEventRow> findByActionOrderByOccurredAtDesc(String action, Pageable pageable);
}