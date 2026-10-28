package com.shoppilot.gateway.web;

import com.shoppilot.gateway.agent.AgentResult;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.agent.EventSink;
import com.shoppilot.gateway.channel.Channel;
import com.shoppilot.gateway.channel.ChannelContext;
import com.shoppilot.gateway.channel.ChannelConversationStore;
import com.shoppilot.gateway.channel.WidgetKeyRegistry;
import com.shoppilot.gateway.identity.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Web Widget（票 184）：公开 key + 访客 id 换租户身份，复用 Agent 与渠道收件箱。
 * 不暴露 JWT；key 只换一个固定演示买家，生产应换成一租户一 key。
 */
@RestController
@RequestMapping("/api/v1/widget")
public class WidgetController {

    private final WidgetKeyRegistry keys;
    private final AgentStateMachine agent;
    private final ChannelConversationStore store;
    private final ChatAdmission admission;

    public WidgetController(WidgetKeyRegistry keys, AgentStateMachine agent,
                            ChannelConversationStore store, ChatAdmission admission) {
        this.keys = keys;
        this.agent = agent;
        this.store = store;
        this.admission = admission;
    }

    public record WidgetRequest(String visitorId, String message) {
    }

    public record WidgetReply(String conversationId, String answer, String status) {
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestHeader(value = "X-Widget-Key", required = false) String widgetKey,
                                  @RequestBody(required = false) WidgetRequest request,
                                  HttpServletRequest servletRequest) {
        WidgetKeyRegistry.WidgetIdentity key = keys.resolve(widgetKey);
        if (key == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "无效的 widget key"));
        }
        String visitorId = request == null ? "" : request.visitorId();
        String message = request == null || request.message() == null ? "" : request.message().trim();
        if (visitorId == null || !visitorId.matches("[A-Za-z0-9_-]{1,64}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "visitorId 只允许字母数字、下划线、短横线"));
        }
        if (message.isBlank() || message.length() > 500) {
            return ResponseEntity.badRequest().body(Map.of("error", "消息不能为空且不超过 500 字"));
        }
        String conversationId = "web:" + visitorId;
        TenantContext.set(new TenantContext.Identity(key.tenantId(), key.customerId(), conversationId));
        ChannelContext.set(Channel.WEB);
        try {
            ChatAdmission.Guard guard = admission.check(servletRequest, message);
            if (!guard.allowed()) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER,
                                String.valueOf(Math.max(1, guard.retryAfterMs() / 1000)))
                        .body(Map.of("error", "当前咨询人数较多，请稍后再试"));
            }
            store.recordInbound(key.tenantId(), key.customerId(), conversationId, "web", message);
            if (store.isAgent(key.tenantId(), conversationId)) {
                return ResponseEntity.ok(new WidgetReply(conversationId, "人工坐席已接入，请稍候。", "AGENT"));
            }
            AgentResult result = agent.run(message, null, EventSink.NOOP);
            String answer = result.answer() == null || result.answer().isBlank()
                    ? "抱歉，我暂时没有查到答案，请换个说法再试。"
                    : result.answer();
            store.recordBotReply(key.tenantId(), conversationId, answer);
            return ResponseEntity.ok(new WidgetReply(conversationId, answer, "BOT"));
        } catch (RuntimeException failure) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "智能助手暂时不可用，请稍后再试"));
        } finally {
            TenantContext.clear();
            ChannelContext.clear();
        }
    }

    @GetMapping("/chat/{visitorId}")
    public ResponseEntity<?> history(@RequestHeader(value = "X-Widget-Key", required = false) String widgetKey,
                                     @PathVariable String visitorId) {
        WidgetKeyRegistry.WidgetIdentity key = keys.resolve(widgetKey);
        if (key == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "无效的 widget key"));
        }
        if (!visitorId.matches("[A-Za-z0-9_-]{1,64}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "visitorId 格式不正确"));
        }
        ChannelConversationStore.Conversation conversation =
                store.find(key.tenantId(), "web:" + visitorId);
        if (conversation == null) {
            return ResponseEntity.ok(Map.of("messages", List.of(), "status", "EMPTY"));
        }
        return ResponseEntity.ok(Map.of(
                "conversationId", conversation.conversationId(),
                "status", conversation.status(),
                "messages", conversation.messages()));
    }
}
