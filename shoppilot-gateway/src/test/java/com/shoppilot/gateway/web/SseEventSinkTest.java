package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.cache.CacheService;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
