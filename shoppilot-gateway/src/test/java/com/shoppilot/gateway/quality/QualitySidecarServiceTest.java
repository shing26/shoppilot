package com.shoppilot.gateway.quality;

import com.shoppilot.tool.audit.AuditTopics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H4：只读 sidecar 缺指标按 0 报，不猜历史；能归并 handoff / risk / friction。 */
class QualitySidecarServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QualitySidecarService service = new QualitySidecarService(registry, null);

    @Test
    @DisplayName("空注册表报零值，不抛错也不伪造数据")
    void reportsZeroesWhenNoTraffic() {
        Map<String, Object> report = service.report();

        assertThat(report.get("scope")).isEqualTo("process-local-cumulative");
        Map<String, Object> handoff = cast(report.get("handoff"));
        assertThat(handoff.get("allowed")).isEqualTo(0.0d);
        assertThat(handoff.get("blocked")).isEqualTo(0.0d);
        assertThat(handoff.get("blockedRatio")).isEqualTo(0.0d);
        Map<String, Object> friction = cast(report.get("friction"));
        assertThat(friction.get("toolSuccessFallbacks")).isEqualTo(0.0d);
        assertThat(friction.get("repeatedSlotAsks")).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("归并 handoff 行、blocked reason、tool risk 与 friction 计数")
    void aggregatesKnownMetrics() {
        Counter.builder("shoppilot_handoff_total").tag("from", "PRE_SALES").tag("to", "AFTER_SALES")
                .tag("result", "ALLOWED").register(registry).increment(2);
        Counter.builder("shoppilot_handoff_total").tag("from", "PRE_SALES").tag("to", "AFTER_SALES")
                .tag("result", "BLOCKED").register(registry).increment(1);
        Counter.builder("shoppilot_handoff_blocked_total").tag("from", "PRE_SALES").tag("to", "AFTER_SALES")
                .tag("reason", "DEPTH_EXHAUSTED").register(registry).increment(1);
        Counter.builder("shoppilot_tool_risk_attempts_total").tag("tool", "APPLY_REFUND")
                .tag("risk", "MONEY_WRITE").tag("hitl", "true").tag("role", "AFTER_SALES")
                .register(registry).increment(3);
        registry.counter("shoppilot_quality_tool_success_fallback_total").increment(4);
        registry.counter("shoppilot_quality_slot_asks_repeated_total").increment(5);
        Counter.builder("shoppilot_quality_slot_asks_repeated_total").tag("phase", "cross_handoff")
                .tag("sameSlot", "true").register(registry).increment(2);
        Counter.builder("shoppilot_handoff_context_loss_total").tag("slot", "orderNo")
                .register(registry).increment(2);
        Timer.builder("shoppilot_handoff_decision_seconds").register(registry)
                .record(java.time.Duration.ofMillis(250));

        Map<String, Object> report = service.report();
        Map<String, Object> handoff = cast(report.get("handoff"));
        assertThat(handoff.get("allowed")).isEqualTo(2.0d);
        assertThat(handoff.get("blocked")).isEqualTo(1.0d);
        assertThat(handoff.get("blockedRatio")).isEqualTo(1.0d / 3.0d);

        Map<String, Object> blockedReasons = cast(handoff.get("blockedByReason"));
        assertThat(blockedReasons).containsEntry("PRE_SALES->AFTER_SALES|DEPTH_EXHAUSTED", 1.0d);

        Map<String, Object> friction = cast(report.get("friction"));
        assertThat(friction.get("toolSuccessFallbacks")).isEqualTo(4.0d);
        assertThat(friction.get("repeatedSlotAsks")).isEqualTo(7.0d);
        assertThat(String.valueOf(friction.get("repeatedSlotAsksByPhase")))
                .contains("cross_handoff").contains("sameSlot");
        assertThat(String.valueOf(friction.get("handoffContextLosses"))).contains("orderNo");

        Map<String, Object> decision = cast(handoff.get("decisionSeconds"));
        assertThat((Double) decision.get("count")).isEqualTo(1.0d);
        assertThat((Double) decision.get("meanSeconds")).isGreaterThan(0d);
    }

    @Test
    @DisplayName("Redis audit 流按 15 分钟窗口聚合坐席动作；过期事件不进分子")
    void aggregatesRecentAuditActions() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        MapRecord<String, Object, Object> recent = record("TICKET_CLAIMED", Instant.now());
        MapRecord<String, Object, Object> old = record("TICKET_RESOLVED",
                Instant.now().minusSeconds(16 * 60));
        when(streams.reverseRange(eq(AuditTopics.AUDIT), any(), any()))
                .thenReturn(List.of(recent, old));

        QualitySidecarService service = new QualitySidecarService(registry, redis);
        Map<String, Object> first = cast(service.report().get("audit"));
        Map<String, Object> second = cast(service.report().get("audit"));

        assertThat(first).isSameAs(second);
        verify(streams, times(1)).reverseRange(eq(AuditTopics.AUDIT), any(), any());
        assertThat(first.get("available")).isEqualTo(true);
        assertThat(first.get("eventsScanned")).isEqualTo(2);
        Map<String, Object> actions = cast(first.get("actions"));
        assertThat(actions).containsEntry("TICKET_CLAIMED", 1.0d);
        assertThat(actions).doesNotContainKey("TICKET_RESOLVED");
    }

    @SuppressWarnings("unchecked")
    private static MapRecord<String, Object, Object> record(String action, Instant occurredAt) {
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getValue()).thenReturn(Map.of(
                "action", action,
                "occurredAt", occurredAt.toString()));
        return record;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
