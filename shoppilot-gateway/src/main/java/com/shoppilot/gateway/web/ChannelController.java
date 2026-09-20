package com.shoppilot.gateway.web;

import com.shoppilot.gateway.agent.AgentResult;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.agent.EventSink;
import com.shoppilot.gateway.channel.Channel;
import com.shoppilot.gateway.channel.ChannelAdapter;
import com.shoppilot.gateway.channel.ChannelContext;
import com.shoppilot.gateway.channel.EmailAdapter;
import com.shoppilot.gateway.channel.EmailReceiptWriter;
import com.shoppilot.gateway.channel.WebhookAdapter;
import com.shoppilot.gateway.feedback.FeedbackService;
import com.shoppilot.gateway.identity.RequestTrace;
import com.shoppilot.gateway.identity.TenantContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 非 web 渠道的入站端点（ADR 0035 / 票 38）：webhook 族（app / miniapp / webhook 标签）整段 JSON
 * 回包；email 走全链路后把结果落成回执工单（无实时回包通道，工单即交付形态）。
 *
 * <p>渠道只是入站标签：身份仍走 JWT、会话归属仍是「店铺 + 买家」（ADR 0025）、
 * 缓存与限流防线与 web 渠道同一条；四条路径共用 {@link ChatAdmission}。
 */
@RestController
@RequestMapping("/api/v1/support")
public class ChannelController {

    private final AgentStateMachine agent;
    private final ChatAdmission admission;
    private final FeedbackService feedbackService;
    private final EmailReceiptWriter receiptWriter;
    private final WebhookAdapter webhookAdapter;
    private final EmailAdapter emailAdapter;

    public ChannelController(AgentStateMachine agent, ChatAdmission admission, FeedbackService feedbackService,
                             EmailReceiptWriter receiptWriter, WebhookAdapter webhookAdapter,
                             EmailAdapter emailAdapter) {
        this.agent = agent;
        this.admission = admission;
        this.feedbackService = feedbackService;
        this.receiptWriter = receiptWriter;
        this.webhookAdapter = webhookAdapter;
        this.emailAdapter = emailAdapter;
    }

    /** app / miniapp / webhook 三个标签共用 webhook 形态（通用 HTTP JSON 入口，无流式）。 */
    @PostMapping("/webhook/{channel}")
    public ResponseEntity<ChannelResponse> webhook(@PathVariable String channel,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        Channel tag = Channel.fromPath(channel);
        if (tag == null || tag == Channel.WEB || tag == Channel.EMAIL) {
            return ResponseEntity.badRequest().body(ChannelResponse.rejected(channel, "渠道仅支持 app / miniapp / webhook"));
        }
        return handle(tag, webhookAdapter, body, request, false);
    }

    /** 邮件渠道：全链路 + 回执工单（ADR 0035；判据「email 全链路落工单」）。 */
    @PostMapping("/email")
    public ResponseEntity<ChannelResponse> email(@RequestBody Map<String, Object> body,
                                                 HttpServletRequest request) {
        return handle(Channel.EMAIL, emailAdapter, body, request, true);
    }

    private ResponseEntity<ChannelResponse> handle(Channel tag, ChannelAdapter adapter, Map<String, Object> body,
                                                   HttpServletRequest request, boolean receipt) {
        ChannelContext.set(tag);
        try {
            ChannelAdapter.NormalizedChat inbound;
            try {
                inbound = adapter.normalize(body);
            } catch (IllegalArgumentException malformed) {
                return ResponseEntity.badRequest().body(ChannelResponse.rejected(tag.label(), malformed.getMessage()));
            }
            ChatAdmission.Guard guard = admission.check(request, inbound.query());
            if (!guard.allowed()) {
                // 与同步端点同语义的 429，回包体带渠道与工单号；Retry-After 供调用方退避
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, guard.retryAfterMs() / 1000)))
                        .body(ChannelResponse.rateLimited(tag.label(), guard.ticketId()));
            }
            AgentResult result = agent.run(inbound.query(), inbound.idempotencyToken(), EventSink.NOOP);
            TenantContext.Identity identity = TenantContext.current();
            feedbackService.noteAnswer(identity.conversationId(),
                    result.intent() == null ? null : result.intent().name(),
                    result.fallbackReason() == null ? null : result.fallbackReason().name(),
                    result.citations(), result.ticketId());
            String receiptTicketId = null;
            if (receipt) {
                // 答案本身是降级（已有可查工单）就不再叠一张回执；正常答案落回执单作为交付形态
                receiptTicketId = result.ticketId() != null ? result.ticketId()
                        : receiptWriter.writeReceipt(inbound.query(), result.answer(), inbound.contact()).orElse(null);
            }
            return ResponseEntity.ok(new ChannelResponse(tag.label(), adapter.streaming(), adapter.canFollowUp(),
                    RequestTrace.traceId(), result.answer(),
                    result.intent() == null ? null : result.intent().name(), result.citations(),
                    result.fallbackReason() == null ? null : result.fallbackReason().name(),
                    result.ticketId(), receiptTicketId, result.promptVersion(), result.trace()));
        } finally {
            ChannelContext.clear();
        }
    }

    /**
     * 整段 JSON 回包（webhook/email 无流式）。@param receiptTicketId 邮件渠道的交付工单号
     */
    public record ChannelResponse(String channel, boolean streaming, boolean canFollowUp, String answerId,
                                  String answer, String intent, List<String> citations, String fallbackReason,
                                  String ticketId, String receiptTicketId, String promptVersion,
                                  List<AgentResult.TraceStep> trace) {

        static ChannelResponse rejected(String channel, String message) {
            return new ChannelResponse(channel, false, false, null, message, null, List.of(), null, null, null, null,
                    List.of());
        }

        static ChannelResponse rateLimited(String channel, String ticketId) {
            return new ChannelResponse(channel, false, false, null, "当前咨询人数较多，请稍后再试。", null, List.of(),
                    "RATE_LIMITED", ticketId, null, null, List.of());
        }
    }
}
