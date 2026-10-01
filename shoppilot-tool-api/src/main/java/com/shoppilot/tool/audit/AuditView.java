package com.shoppilot.tool.audit;

import java.time.Instant;

/** 审计事件的对外形状（round23 票 71）。放共享库是因为调试台与未来的坐席台都要读它。 */
public record AuditView(
        String eventId,
        String action,
        String objectType,
        String objectId,
        String actor,
        String detail,
        Instant occurredAt) {
}