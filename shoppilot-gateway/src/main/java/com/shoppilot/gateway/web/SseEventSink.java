package com.shoppilot.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.AgentState;
import com.shoppilot.gateway.agent.EventSink;
import com.shoppilot.gateway.agent.FallbackReason;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.view.ToolStatus;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 状态机出口到 SSE 帧的适配器：事件名与字段集合与 PLAN.md 的契约一一对应。
 *
 * <p>推送由状态转移驱动，不在业务代码里手写 send——这是自研状态机最实在的收益。
 * 客户端断开后 send 会抛异常，这里只记一次并静默丢弃后续帧，编排本身继续跑完以便写回缓存。
 */
public class SseEventSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(SseEventSink.class);

    private final SseEmitter emitter;
    private final ObjectMapper mapper;
    private final String traceId;
    private final Timer ttftTimer;
    private final long startedAtNanos;
    private final AtomicBoolean firstTokenSent = new AtomicBoolean();
    private volatile boolean clientGone;

    public SseEventSink(SseEmitter emitter, ObjectMapper mapper, String traceId, Timer ttftTimer,
                        long startedAtNanos) {
        this.emitter = emitter;
        this.mapper = mapper;
        this.traceId = traceId;
        this.ttftTimer = ttftTimer;
        this.startedAtNanos = startedAtNanos;
    }

    @Override
    public void status(AgentState state, String detail) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", state.name());
        data.put("label", state.label());
        data.put("ts", System.currentTimeMillis());
        data.put("detail", detail == null ? "" : detail);
        send("status", data);
    }

    @Override
    public void meta(String conversationId, Intent intent, CacheService.Layer cacheLayer) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversationId);
        data.put("traceId", traceId);
        data.put("intent", intent == null ? "" : intent.name());
        data.put("cacheHit", cacheLayer == CacheService.Layer.L1 || cacheLayer == CacheService.Layer.L2);
        data.put("cacheLayer", cacheLayer.name());
        send("meta", data);
    }

    @Override
    public void toolExecuting(ToolName tool, String label) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", tool == null ? "" : tool.apiName());
        data.put("label", label);
        send("tool_executing", data);
    }

    @Override
    public void duplicateSubmit(ToolName tool, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", tool == null ? "" : tool.apiName());
        data.put("message", message);
        send("duplicate_submit", data);
    }

    @Override
    public void toolResult(ToolName tool, ToolStatus status, String summary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", tool == null ? "" : tool.apiName());
        data.put("status", status == null ? "" : status.name());
        data.put("summary", summary);
        send("tool_result", data);
    }

    @Override
    public void slotAsk(String slot, String question) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("slot", slot);
        data.put("question", question);
        send("slot_ask", data);
    }

    @Override
    public void fallback(FallbackReason reason, String ticketId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason.name());
        if (ticketId != null) {
            data.put("ticketId", ticketId);
        }
        send("fallback", data);
    }

    @Override
    public void token(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (firstTokenSent.compareAndSet(false, true)) {
            ttftTimer.record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
        }
        sendRaw("token", quote(delta));
    }

    /** done 事件由控制器在编排收尾时发出，字段见 PLAN.md。 */
    public void done(String answerId, List<String> citations, int promptTokens, int completionTokens) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("promptTokens", promptTokens);
        usage.put("completionTokens", completionTokens);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answerId", answerId);
        data.put("citations", citations);
        data.put("usage", usage);
        send("done", data);
    }

    public boolean clientGone() {
        return clientGone;
    }

    /**
     * 编排抛出未预期异常时收尾用：如实告诉客户端这一轮中断了，不伪装成任何一种降级。
     * 客户端以"没收到 done 事件"判定失败。
     */
    public void aborted(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", "ABORTED");
        data.put("label", "本轮中断");
        data.put("ts", System.currentTimeMillis());
        data.put("detail", message == null ? "" : message);
        send("status", data);
    }

    /** 被限流不是失败：仍走同一条 SSE 通道，客户端不需要为它准备第二套错误处理。 */
    public void rateLimited(long retryAfterMs, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("retryAfterMs", retryAfterMs);
        data.put("message", message);
        send("rate_limited", data);
    }

    private String quote(String delta) {
        try {
            return mapper.writeValueAsString(delta);
        } catch (JsonProcessingException failure) {
            return "\"\"";
        }
    }

    private void send(String event, Map<String, Object> data) {
        try {
            sendRaw(event, mapper.writeValueAsString(data));
        } catch (JsonProcessingException failure) {
            log.warn("事件 {} 序列化失败: {}", event, failure.getMessage());
        }
    }

    private void sendRaw(String event, String json) {
        if (clientGone) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(json));
        } catch (Exception disconnected) {
            clientGone = true;
            log.debug("SSE 客户端断开，停止推帧: {}", disconnected.getMessage());
        }
    }
}
