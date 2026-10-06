package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.service.BizMockService;
import com.shoppilot.tool.audit.ActorHeaders;
import com.shoppilot.tool.view.RefundView;
import com.shoppilot.tool.view.ToolResponse;
import com.shoppilot.tool.view.ToolStatus;
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
     * 放行或驳回。{@code note} 接受但不落业务表（审查人自用的理由在 v1 没有消费者，见 ADR 0047 决策四）；
     * 但它会进审计事件的 detail——放行是不可逆的资金动作，理由得跟着留痕一起走。
     *
     * <p><b>{@code X-Reviewer} 已退役</b>（round25 票 82 / ADR 0058 第 4 条）：那个头是调用方自报的，
     * 带上内部 token 就能写成任何名字，于是「谁放了这笔款」一度没有任何根据。
     * 现在只有 {@code X-Actor}（名字）与 {@code X-Actor-Authenticated}（这个名字是否来自已验签令牌），
     * 两个都由网关从令牌解出来再下发，本服务自己不接受任何来自客户端的身份声明。
     *
     * <p><b>B3 起加硬线</b>（ADR 0063）：资金动作的责任人必须是已验签的账号。
     * 即使网关被绕过（如直连 biz-mock），未认证的审核也不能落库——403 空体，
     * 与 {@code badRequest().build()} 同风格（错误信息由网关层 {@code role.denied} 承担）。
     */
    @PostMapping("/{refundId}/review")
    public ResponseEntity<ToolResponse<RefundView>> review(@PathVariable String refundId,
                                                           @RequestBody ReviewRequest request,
                                                           @RequestHeader(value = "X-Actor", required = false)
                                                           String actor,
                                                           @RequestHeader(value = "X-Actor-Authenticated",
                                                                   required = false) String actorAuthenticated) {
        if (!"true".equalsIgnoreCase(actorAuthenticated == null ? null : actorAuthenticated.trim())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            ToolResponse<RefundView> response = service.reviewRefund(refundId, request.decision(), request.note(),
                    ActorHeaders.of(actor, actorAuthenticated));
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
