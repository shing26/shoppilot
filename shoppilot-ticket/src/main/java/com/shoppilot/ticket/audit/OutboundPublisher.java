package com.shoppilot.ticket.audit;

import com.shoppilot.ticket.tenant.TenantContextHolder;
import com.shoppilot.tool.audit.AuditTopics;
import com.shoppilot.tool.audit.ChannelOutboundEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 渠道出站的生产端（round26 票 87 / ADR 0059）。
 *
 * <p>与 {@link AuditPublisher} 是同一种取舍：**发布失败只记 warn，绝不抛给业务**。
 * 坐席点了「处理完成」却因为消息发不出去而回滚，是不可接受的；丢一条出站是缺口，
 * 而缺口会被计数与门禁看见（票 89）。
 *
 * <p><b>「没有目标就不发」也要计数</b>：web 渠道的降级单有 {@code channel} 没有 {@code contact}
 * （买家就在浏览器里等）。那种情况**不发事件**，但它必须让读数上看得见——
 * 否则「发出去了 0 条」与「都发成功了」在面板上长得一样，而前者其实是正常情况。
 */
@Component
public class OutboundPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboundPublisher.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry registry;
    private final Counter noTargetCounter;

    public OutboundPublisher(StringRedisTemplate redis, MeterRegistry registry) {
        this.redis = redis;
        this.registry = registry;
        this.noTargetCounter = Counter.builder("shoppilot_outbound_no_target_total").register(registry);
    }

    /**
     * 发一条出站事件。
     *
     * @param channel 买家当初问的渠道；为空或 {@code web} 都不发（见类注释）
     * @param target  投递目标；为空就不发
     * @param ticketId 指回那张工单——消费端对账与失败落单都要靠它指名
     * @param body    送出去的正文（坐席的处理结论）
     * @return 事件的 eventId；**没发出去时返回 null**（不要用 Optional：那不是「查不到」，是「按规则不发」）
     */
    public String publish(String channel, String target, String ticketId, String body) {
        if (channel == null || channel.isBlank() || target == null || target.isBlank()
                || "web".equalsIgnoreCase(channel.trim())) {
            noTargetCounter.increment();
            log.info("工单 {} 没有可投递的目标（channel={}），按规则不发事件", ticketId, channel);
            return null;
        }
        ChannelOutboundEvent event = new ChannelOutboundEvent(UUID.randomUUID().toString(),
                ChannelOutboundEvent.CURRENT_SCHEMA, TenantContextHolder.tenantId(), channel.trim(), target.trim(),
                body, Instant.now(), ticketId);
        Map<String, String> fields = new HashMap<>();
        fields.put("eventId", event.eventId());
        fields.put("schemaVersion", String.valueOf(event.schemaVersion()));
        fields.put("tenantId", event.tenantId());
        fields.put("channel", event.channel());
        fields.put("target", event.target());
        fields.put("ticketId", event.ticketId() == null ? "" : event.ticketId());
        fields.put("body", event.body() == null ? "" : event.body());
        fields.put("occurredAt", event.occurredAt().toString());
        try {
            redis.opsForStream().add(AuditTopics.CHANNEL_OUTBOUND, fields);
            // 带标签的计数要**每次按标签取**：Micrometer 的 Counter 一旦注册就不能再加标签，
            // 对已注册的实例调 .tag() 会直接抛异常。registry.counter(name, tags) 自带缓存。
            registry.counter("shoppilot_outbound_published_total", "channel", event.channel()).increment();
            return event.eventId();
        } catch (RuntimeException unreachable) {
            // 缺口，不是事故：见类注释。计数已经加上「本该发」，失败这一格留给票 89 的门禁看。
            log.warn("渠道出站发布失败 ticket={} channel={}: {}", ticketId, channel, unreachable.getMessage());
            return null;
        }
    }
}