package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.service.FeedbackService;
import com.shoppilot.tool.audit.ActorHeaders;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 满意度反馈端点（ADR 0039 / 票 37）。浏览器不直连本服务，一律经网关代理。 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping
    public FeedbackService.FeedbackView create(@RequestBody CreateFeedbackRequest request) {
        return service.createFeedback(request.customerId(), request.conversationId(), request.verdict(),
                request.reason(), request.signals(), request.ruleIds(), request.ticketId());
    }

    /** ingest 待复核队列：DOWN 且未复核的行，人工从这里认领。 */
    @GetMapping("/review-queue")
    public List<FeedbackService.FeedbackView> reviewQueue() {
        return service.reviewQueue();
    }

    /**
     * 复核完成。
     *
     * <p>{@code X-Reviewer} 已退役（round25 票 82 / ADR 0058）：它由调用方自报，带上内部 token
     * 就能写成任何名字。现在只认 {@code X-Actor} 与 {@code X-Actor-Authenticated} 两个头，
     * 都由网关从已验签的令牌解出来再下发。
     */
    @PatchMapping("/{feedbackId}/review")
    public ResponseEntity<FeedbackService.FeedbackView> markReviewed(
            @PathVariable String feedbackId,
            @RequestBody Map<String, @NotBlank String> body,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestHeader(value = "X-Actor-Authenticated", required = false) String actorAuthenticated) {
        try {
            return ResponseEntity.ok(service.markReviewed(feedbackId, ActorHeaders.of(actor, actorAuthenticated)));
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException illegalTransition) {
            // 409 而不是 400：请求本身没写错，是这行反馈当前状态不允许这么流转
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    public record CreateFeedbackRequest(String customerId, String conversationId, String verdict, String reason,
                                        String signals, List<String> ruleIds, String ticketId) {
    }
}
