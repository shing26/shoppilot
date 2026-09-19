package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.service.FeedbackService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PatchMapping("/{feedbackId}/review")
    public ResponseEntity<FeedbackService.FeedbackView> markReviewed(@PathVariable String feedbackId,
                                                                     @RequestBody Map<String, @NotBlank String> body) {
        try {
            return ResponseEntity.ok(service.markReviewed(feedbackId));
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
