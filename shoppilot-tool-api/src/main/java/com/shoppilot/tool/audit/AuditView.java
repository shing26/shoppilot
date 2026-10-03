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
        Instant occurredAt,
        /** actor 是否来自已验签的令牌（票 82）。查询面要能只挑出未认证的那些。 */
        boolean actorAuthenticated) {

    /** 票 82 之前的七参构造：落到「未认证」，方向与 {@code AuditEvent} 的默认一致。 */
    public AuditView(String eventId, String action, String objectType, String objectId, String actor,
                     String detail, Instant occurredAt) {
        this(eventId, action, objectType, objectId, actor, detail, occurredAt, false);
    }
}