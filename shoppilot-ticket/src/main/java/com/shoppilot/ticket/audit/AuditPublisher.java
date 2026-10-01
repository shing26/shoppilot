package com.shoppilot.ticket.audit;

import com.shoppilot.tool.audit.AuditActions;
import com.shoppilot.tool.audit.AuditEvent;
import com.shoppilot.tool.audit.AuditTopics;
import com.shoppilot.ticket.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 审计事件的生产端（round23 票 72，ADR 0054/0056）。
 *
 * <p>它是 biz-mock 侧那套审计设施的**精简对偶**：同一个契约（{@code tool-api} 的 {@code AuditEvent}）、
 * 同一条流（{@code shoppilot:audit}），落表与查询仍在 biz-mock 那边。两个服务连同一条 Redis 流，
 * 谁的坐席动作都能留痕，而审计表仍然只有一份——**不造第二本账**。
 *
 * <p>为什么不抽一个共享的 client 模块：那是又一次跨模块重构（票 72 已经够大了），
 * 登记为后续项。它的代价是这两个类会各自演化；触发条件 = 第三个服务也要发审计事件。
 *
 * <p><b>发布失败不抛给业务</b>：坐席点了「领取」却因为审计发不出去而失败，是不可接受的。
 * 丢一条审计是缺口（照登），把人卡住是事故。
 */
@Component
public class AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditPublisher.class);

    private final StringRedisTemplate redis;

    public AuditPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void publish(String action, String objectType, String objectId, String actor, String detail) {
        AuditEvent event = new AuditEvent(UUID.randomUUID().toString(), AuditEvent.CURRENT_SCHEMA, action,
                objectType, objectId, TenantContextHolder.tenantId(),
                actor == null || actor.isBlank() ? AuditActions.SYSTEM_ACTOR : actor, detail, Instant.now());
        Map<String, String> fields = new HashMap<>();
        fields.put("eventId", event.eventId());
        fields.put("schemaVersion", String.valueOf(event.schemaVersion()));
        fields.put("action", event.action());
        fields.put("objectType", event.objectType());
        fields.put("objectId", event.objectId());
        fields.put("tenantId", event.tenantId());
        fields.put("actor", event.actor());
        fields.put("detail", event.detail() == null ? "" : event.detail());
        fields.put("occurredAt", event.occurredAt().toString());
        try {
            redis.opsForStream().add(AuditTopics.AUDIT, fields);
        } catch (RuntimeException unreachable) {
            log.warn("审计事件发布失败 action={} object={}: {}", action, objectId, unreachable.getMessage());
        }
    }
}