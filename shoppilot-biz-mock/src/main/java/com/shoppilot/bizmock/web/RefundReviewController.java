package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.service.BizMockService;
import com.shoppilot.tool.view.RefundView;
import com.shoppilot.tool.view.ToolResponse;
import com.shoppilot.tool.view.ToolStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 退款审核端点（ADR 0047）。先申请落 {@code PENDING_REVIEW}，再由人工在此放行或驳回 ——
 * 一道只存在于 curl 里的闸门在演示现场等于不存在，所以审核队列同时有调试台面板（票 61）。
 *
 * <p>浏览器不直连本服务，一律经网关代理；调用方必须带内部 token 与租户上下文（{@link InternalAuthFilter}）。
 */
@RestController
@RequestMapping("/api/refunds")
public class RefundReviewController {

    private final BizMockService service;

    public RefundReviewController(BizMockService service) {
        this.service = service;
    }

    /** 审核队列：只列待审核的退款单。 */
    @GetMapping("/pending")
    public List<RefundView> pending() {
        return service.listPendingRefunds();
    }

    /**
     * 放行或驳回。{@code note} 接受但不落库（审查人自用的理由在 v1 没有消费者，见 ADR 0047 决策四）。
     */
    @PostMapping("/{refundId}/review")
    public ResponseEntity<ToolResponse<RefundView>> review(@PathVariable String refundId,
                                                           @RequestBody ReviewRequest request) {
        try {
            ToolResponse<RefundView> response = service.reviewRefund(refundId, request.decision(), request.note());
            if (response.status() == ToolStatus.NOT_FOUND) {
                return ResponseEntity.notFound().build();
            }
            if (response.status() == ToolStatus.STATE_NOT_ALLOWED) {
                // 409 而不是 400：请求本身没写错，是这张单当前状态不允许再审
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException unknownDecision) {
            return ResponseEntity.badRequest().build();
        }
    }

    public record ReviewRequest(String decision, String note) {
    }
}
