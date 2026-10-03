package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.audit.AuditService;
import com.shoppilot.bizmock.domain.AuditEventRow;
import com.shoppilot.tool.audit.AuditView;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计查询（round23 票 71 / ADR 0056）。
 *
 * <p>读之前先收流（惰性消费），所以查得到的事件不依赖任何后台线程还活着——这一点在
 * 「进程重启后审计还在不在」上尤其要紧。
 *
 * <p>租户隔离走 {@code @TenantId} + {@code InternalAuthFilter} 的租户上下文；跨店审计查不到。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final int MAX_LIMIT = 200;

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AuditView> list(@RequestParam(required = false) String action,
                                @RequestParam(defaultValue = "50") int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return auditService.recent(action, capped).stream().map(AuditController::toView).toList();
    }

    private static AuditView toView(AuditEventRow row) {
        return new AuditView(row.getEventId(), row.getAction(), row.getObjectType(), row.getObjectId(),
                row.getActor(), row.getDetail(), row.getOccurredAt(), row.isActorAuthenticated());
    }
}