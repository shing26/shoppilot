package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.AgentResult;
import com.shoppilot.gateway.cache.CacheService;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * SSE meta 载荷的 0 token 契约测试：promptVersion（ADR 0037）与 channel（ADR 0035）
 * 必须随 meta 回显——用 mock 的 SseEmitter 捕获真实发出去的事件体，不启动容器。
 */
class SseEventSinkTest {

    @Test
    @DisplayName("meta 事件携带 promptVersion 与 channel")
    void metaCarriesPromptVersionAndChannel() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseEventSink sink = new SseEventSink(emitter, new ObjectMapper(), "trace-1", "v1.0.0", "app",
                mock(Timer.class), System.nanoTime());

        sink.meta("conv-1", null, CacheService.Layer.NONE);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captured = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captured.capture());
        // build() 是分段集合（event: / data: / 空行）：取 JSON 那一段
        String json = captured.getValue().build().stream()
                .map(part -> String.valueOf(part.getData()))
                .filter(data -> data.stripLeading().startsWith("{"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("meta 事件里没有 data 段"));
        JsonNode meta = new ObjectMapper().readTree(json);
        assertEquals("v1.0.0", meta.path("promptVersion").asText());
        assertEquals("app", meta.path("channel").asText());
        assertEquals("conv-1", meta.path("conversationId").asText());
    }

    @Test
    @DisplayName("done 事件携带 plan 与 context，且空值归一为数组/零值对象（票 48/49）")
    void doneCarriesPlanAndContextWithNormalizedEmpties() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseEventSink sink = new SseEventSink(emitter, new ObjectMapper(), "trace-1", "v1.0.0", "app",
                mock(Timer.class), System.nanoTime());

        sink.done("ans-1", List.of("return-01-7day-basic"), 120, 30,
                List.of(new AgentResult.PlanStep("queryOrderDetail", "OK", 12L, Map.of("orderNo", "90001"))),
                new AgentResult.ContextComposition(List.of("return-01-7day-basic"), 2, 88));

        ArgumentCaptor<SseEmitter.SseEventBuilder> captured = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captured.capture());
        JsonNode done = new ObjectMapper().readTree(jsonDataOf(captured.getValue()));
        assertEquals("ans-1", done.path("answerId").asText());
        assertEquals(120, done.path("usage").path("promptTokens").asInt());
        assertEquals("queryOrderDetail", done.path("plan").get(0).path("tool").asText());
        assertEquals("OK", done.path("plan").get(0).path("status").asText());
        assertEquals("90001", done.path("plan").get(0).path("arguments").path("orderNo").asText());
        assertEquals(2, done.path("context").path("historyTurns").asInt());
        assertEquals("return-01-7day-basic", done.path("context").path("ruleIds").get(0).asText());
    }

    @Test
    @DisplayName("done 事件的两个新字段不做判空分支：null 进来出去的是空数组与零值对象（票 48/49）")
    void doneNormalizesNullsInsteadOfEmittingNull() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseEventSink sink = new SseEventSink(emitter, new ObjectMapper(), "trace-1", "v1.0.0", "app",
                mock(Timer.class), System.nanoTime());

        sink.done("ans-2", List.of(), 0, 0, null, null);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captured = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captured.capture());
        JsonNode done = new ObjectMapper().readTree(jsonDataOf(captured.getValue()));
        assertTrue(done.path("plan").isArray(), "plan 必须是空数组而不是 null：" + done.path("plan"));
        assertEquals(0, done.path("plan").size());
        assertTrue(done.path("context").isObject(), "context 必须是零值对象而不是 null：" + done.path("context"));
        assertEquals(0, done.path("context").path("historyTurns").asInt());
    }

    private static String jsonDataOf(SseEmitter.SseEventBuilder builder) {
        // build() 是分段集合（event: / data: / 空行）：取 JSON 那一段
        return builder.build().stream()
                .map(part -> String.valueOf(part.getData()))
                .filter(data -> data.stripLeading().startsWith("{"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("事件里没有 data 段"));
    }
}
