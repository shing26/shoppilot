package com.shoppilot.gateway.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.identity.RequestTrace;
import com.shoppilot.gateway.identity.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反馈账本的 0 token 单测（ADR 0039 / 票 37）：重问窗口、negative 计数、显式点踩的
 * 关联字段与复核队列标记、下游不可达时的如实降级。
 */
class FeedbackServiceTest {

    private SimpleMeterRegistry registry;
    private final List<Map<String, Object>> posted = new java.util.ArrayList<>();
    /** 依次返回的落库回执：DOWN 行 PENDING、UP 行 NONE，与 biz-mock 的真实行为一致。 */
    private final java.util.Deque<String> responses = new java.util.ArrayDeque<>(
            List.of("{\"id\":\"FB-1\",\"reviewStatus\":\"PENDING\"}", "{\"id\":\"FB-2\",\"reviewStatus\":\"NONE\"}"));
    private boolean downstreamUp = true;
    private FeedbackService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        posted.clear();
        // recordExplicit 从身份上下文取 customerId（与生产一致：没验签身份就没有反馈主体）
        TenantContext.Identity identity = new TenantContext.Identity("T001", "C155", "conv-test");
        TenantContext.set(identity);
        RequestTrace.bind(identity);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        RequestTrace.clear();
    }

    private FeedbackService serviceWithWindow(Duration window) {
        return new FeedbackService(new ObjectMapper(), registry, payload -> {
            posted.add(payload);
            return downstreamUp ? responses.poll() : null;
        }, window);
    }

    private double implied(String kind) {
        var counter = registry.find("shoppilot_feedback_implied_total").tag("kind", kind).counter();
        return counter == null ? 0.0d : counter.count();
    }

    @Test
    @DisplayName("重问同意图且在窗口内 → implied_dissatisfied；换意图或窗口过期不计数")
    void repeatedSameIntentCountsOncePerRepeat() {
        service = serviceWithWindow(Duration.ofMinutes(30));
        service.noteAnswer("conv-1", "ACTION_ORDER", null, List.of(), null);
        assertEquals(0.0d, implied("implied_dissatisfied"));

        service.noteAnswer("conv-1", "ACTION_ORDER", null, List.of(), null);
        assertEquals(1.0d, implied("implied_dissatisfied"), "同会话同意图重问");

        service.noteAnswer("conv-1", "ACTION_LOGISTICS", null, List.of(), null);
        assertEquals(1.0d, implied("implied_dissatisfied"), "换了意图不算重问");

        service.noteAnswer("conv-1", "ACTION_ORDER", null, List.of(), null);
        assertEquals(1.0d, implied("implied_dissatisfied"), "跟在换过的意图后面不算重问（判相邻轮次）");

        service.noteAnswer("conv-1", "ACTION_ORDER", null, List.of(), null);
        assertEquals(2.0d, implied("implied_dissatisfied"), "相邻同意图再次出现又算");

        // 窗口为 0 的极端形态：任何重问都算过期，用独立 registry 隔离计数
        SimpleMeterRegistry expiredRegistry = new SimpleMeterRegistry();
        FeedbackService expired = new FeedbackService(new ObjectMapper(), expiredRegistry, payload -> "{\"id\":\"FB-X\",\"reviewStatus\":\"PENDING\"}",
                Duration.ZERO);
        expired.noteAnswer("conv-2", "ACTION_ORDER", null, List.of(), null);
        expired.noteAnswer("conv-2", "ACTION_ORDER", null, List.of(), null);
        var expiredCounter = expiredRegistry.find("shoppilot_feedback_implied_total")
                .tag("kind", "implied_dissatisfied").counter();
        assertEquals(0.0d, expiredCounter == null ? 0.0d : expiredCounter.count(), "窗口为 0（过期形态）不计数");
    }

    @Test
    @DisplayName("走到 FALLBACK → negative 计数，与显式 verdict 分开口径")
    void fallbackCountsNegative() {
        service = serviceWithWindow(Duration.ofMinutes(30));
        service.noteAnswer("conv-1", "ACTION_ORDER", "TOOL_UNAVAILABLE", List.of(), null);
        assertEquals(1.0d, implied("negative"));
        service.noteAnswer("conv-1", "ACTION_ORDER", null, List.of(), null);
        assertEquals(1.0d, implied("negative"), "正常回答不计 negative");
    }

    @Test
    @DisplayName("点踩：关联线索随载荷落库，会话内走过的降级随行展示，DOWN 进复核队列；点赞不进")
    void explicitDownLinksTrailAndEntersQueue() {
        service = serviceWithWindow(Duration.ofMinutes(30));
        service.noteAnswer("conv-1", "ACTION_ORDER", "TOOL_UNAVAILABLE", List.of("POLICY-RETURN-01"), "T-900");

        FeedbackService.Ack down = service.recordExplicit("conv-1", "DOWN", "答案不对");
        assertEquals("FB-1", down.feedbackId());
        assertTrue(down.reviewQueued());
        assertEquals("T-900", down.ticketId());
        assertEquals(List.of("POLICY-RETURN-01"), down.ruleIds());
        assertEquals("DOWN", posted.get(0).get("verdict"));
        assertEquals("negative", posted.get(0).get("signals"), "会话里走过的降级要随行展示");

        FeedbackService.Ack up = service.recordExplicit("conv-1", "UP", null);
        assertFalse(up.reviewQueued(), "点赞不进复核队列");
        assertEquals("UP", posted.get(1).get("verdict"));

        assertEquals(1.0d, registry.get("shoppilot_feedback_explicit_total").tag("verdict", "DOWN").counter().count());
        assertEquals(1.0d, registry.get("shoppilot_feedback_explicit_total").tag("verdict", "UP").counter().count());
    }

    @Test
    @DisplayName("下游不可达：reviewQueued=false 如实返回，不假装成功")
    void downstreamFailureReportsHonestly() {
        downstreamUp = false;
        service = serviceWithWindow(Duration.ofMinutes(30));
        FeedbackService.Ack ack = service.recordExplicit("conv-1", "DOWN", null);
        assertFalse(ack.reviewQueued());
        assertEquals(null, ack.feedbackId());
    }

    @Test
    @DisplayName("无线索的会话也能点踩：载荷里不带关联字段")
    void explicitWithoutTrailStillRecords() {
        service = serviceWithWindow(Duration.ofMinutes(30));
        FeedbackService.Ack ack = service.recordExplicit("unknown-conv", "DOWN", null);
        Map<String, Object> payload = posted.get(0);
        assertFalse(payload.containsKey("ruleIds") && payload.get("ruleIds") != null);
        assertTrue(payload.containsKey("conversationId"));
        assertFalse(ack.reviewQueued() && ack.feedbackId() == null);
    }
}
