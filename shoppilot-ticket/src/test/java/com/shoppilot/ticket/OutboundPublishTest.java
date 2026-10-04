package com.shoppilot.ticket;

import com.shoppilot.ticket.audit.OutboundPublisher;
import com.shoppilot.ticket.tenant.TenantContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static com.shoppilot.tool.audit.AuditTopics.CHANNEL_OUTBOUND;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 渠道出站的生产端（round26 票 87 / ADR 0059）。
 *
 * <p>本类只断「发不发、发了什么」；**真的投不投得出去是票 88 的事**。两者的边界写在这里：
 * 生产端拿到的是「该发的都发了」，消费端拿到的是「该送的都送了」，缺哪一半都算断链。
 */
class OutboundPublishTest {

    /** HK/HV 是 String 不是 Object：{@code StringRedisTemplate extends RedisTemplate<String,String>}。 */
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, String, String> streams = mock(StreamOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void stubStream() {
        doReturn(streams).when(redis).opsForStream();
        doReturn(RecordId.of("1-0")).when(streams).add(anyString(), anyMap());
        TenantContextHolder.set("T001", "C001");
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("有渠道有目标：发出一条事件，带上工单号（消费端要靠它指名是哪张单的事）")
    void publishesWhenThereIsATarget() {
        String eventId = new OutboundPublisher(redis, registry)
                .publish("webhook", "https://shop.example/hook", "T-77", "已为您补发配件");

        assertThat(eventId).isNotBlank();
        Map<String, String> sent = captured();
        assertThat(field(sent, "channel")).isEqualTo("webhook");
        assertThat(field(sent, "target")).isEqualTo("https://shop.example/hook");
        assertThat(field(sent, "ticketId")).isEqualTo("T-77");
        assertThat(field(sent, "body")).isEqualTo("已为您补发配件");
        assertThat(field(sent, "tenantId")).isEqualTo("T001");
        assertThat(registry.get("shoppilot_outbound_published_total").tag("channel", "webhook").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("没有目标就不发：web 渠道 / 空 contact / 空 channel 三种都不发，但计数要 +1")
    void doesNotPublishWithoutATarget() {
        OutboundPublisher publisher = new OutboundPublisher(redis, registry);

        assertThat(publisher.publish("web", null, "T-78", "已处理")).as("web 渠道没有送回去这件事").isNull();
        assertThat(publisher.publish("email", null, "T-79", "已处理")).as("空目标").isNull();
        assertThat(publisher.publish(null, "https://x/h", "T-80", "已处理")).as("空渠道").isNull();

        verify(redis, never()).opsForStream();
        // 这一格是承重的：没有它，「发出去了 0 条」与「都发成功了」在面板上长得一样
        assertThat(registry.get("shoppilot_outbound_no_target_total").counter().count())
                .as("按规则不发也要留痕").isEqualTo(3.0d);
        assertThat(registry.find("shoppilot_outbound_published_total").counters())
                .as("没发出去的路径上，发布计数一个都不该注册").isEmpty();
    }

    @Test
    @DisplayName("Redis 不可用时不抛给调用方：发不出去是缺口，不是事故")
    void publishingFailureNeverBreaksTheCaller() {
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("连不上"))
                .when(streams).add(anyString(), anyMap());

        OutboundPublisher publisher = new OutboundPublisher(redis, registry);

        // 不抛异常就是这一格的全部；返回值 null 表示「没发出去」，调用方据实处理
        assertThat(publisher.publish("webhook", "https://shop.example/hook", "T-81", "x")).isNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> captured() {
        ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
        verify(streams).add(eq(CHANNEL_OUTBOUND), values.capture());
        return values.getValue();
    }

    private String field(Map<String, String> record, String key) {
        return record.get(key);
    }
}