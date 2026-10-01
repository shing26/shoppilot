package com.shoppilot.bizmock.service;

import com.shoppilot.bizmock.audit.AuditService;
import com.shoppilot.bizmock.domain.Feedback;
import com.shoppilot.bizmock.repo.FeedbackRepository;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.audit.AuditActions;
import com.shoppilot.tool.workitem.TicketSource;
import com.shoppilot.bizmock.workitem.WorkItemClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/** 满意度反馈的落点与复核队列（ADR 0039 / 票 37）。与工单同库同生命周期：审计资产，不是热数据。 */
@Service
public class FeedbackService {

    private static final AtomicLong FEEDBACK_SEQ = new AtomicLong();

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final FeedbackRepository feedbackRepository;
    private final WorkItemClient workItemClient;
    private final AuditService auditService;

    public FeedbackService(FeedbackRepository feedbackRepository, WorkItemClient workItemClient,
                           AuditService auditService) {
        this.feedbackRepository = feedbackRepository;
        this.workItemClient = workItemClient;
        this.auditService = auditService;
    }

    /** @param reviewStatus DOWN → PENDING（进复核队列）；UP → NONE（只计不审）。 */
    @Transactional
    public FeedbackView createFeedback(String customerId, String conversationId, String verdict, String reason,
                                       String signals, List<String> ruleIds, String ticketId) {
        Instant now = Instant.now();
        String id = "FB" + now.toEpochMilli() + "-" + Long.toUnsignedString(FEEDBACK_SEQ.getAndIncrement(), 36);
        Feedback feedback = new Feedback(id, TenantContextHolder.tenantId(), customerId, conversationId, verdict,
                truncate(reason, 512), truncate(signals, 128), ruleIds == null ? "" : String.join(",", ruleIds),
                ticketId, "DOWN".equals(verdict) ? "PENDING" : "NONE", now);
        Feedback saved = feedbackRepository.save(feedback);
        if ("PENDING".equals(saved.getReviewStatus())) {
            openReviewWorkItem(saved, reason);
        }
        return toView(saved);
    }

    /**
     * 点踩进复核队列时同时开一张复核工单（round23 票 69/72）。
     *
     * <p>它和会话里那张降级单是**两件事**：降级单结的是「这次对话办不了」，复核单结的是
     * 「这句答案的内容对不对」。所以不覆盖 {@code feedback.ticketId}（那列指回升级单），
     * 复核单的 id 走 payload 指回本条 feedback。
     *
     * <p>UP 不开单：只计不审，没有人工要处理的事。
     *
     * <p>工单数据在工单服务（票 72），所以这里是跨进程调用：开单失败**不阻断**复核状态流转——
     * 复核队列的真源是 {@code review_status}，工单是它的工作项，缺了就少一个入口，不是数据丢了。
     */
    private void openReviewWorkItem(Feedback feedback, String reason) {
        String ticketId = workItemClient.create(TicketSource.FEEDBACK_REVIEW, feedback.getCustomerId(),
                "FEEDBACK_REVIEW", "会话 " + feedback.getConversationId() + " 的答复待复核",
                reason == null ? "" : reason, null,
                "{\"feedbackId\":\"" + feedback.getId() + "\",\"conversationId\":\""
                        + feedback.getConversationId() + "\"}");
        if (ticketId == null) {
            log.warn("复核工单 {} 没开成；复核队列仍可处理（真源是 feedback.review_status）", feedback.getId());
        }
    }

    /** ingest 待复核队列：本店范围内 PENDING 状态的反馈，按时间倒序。 */
    @Transactional(readOnly = true)
    public List<FeedbackView> reviewQueue() {
        return feedbackRepository.findByReviewStatusOrderByCreatedAtDesc("PENDING").stream()
                .map(this::toView).toList();
    }

    /**
     * 人工复核完成：PENDING → REVIEWED。复核本身不触发任何自动改写（ADR 0039）。
     *
     * <p>但复核**是**一次人工动作，所以要留痕（ADR 0056）：谁复核了哪条反馈。
     * {@code reviewer} 同样是调用方自报的（身份域是下一轮），进审计不进权限。
     */
    @Transactional
    public FeedbackView markReviewed(String id, String reviewer) {
        FeedbackView view = transition(id, feedback -> {
            if (!"PENDING".equals(feedback.getReviewStatus())) {
                throw new IllegalStateException("feedback " + id + " 不在 PENDING 状态，不能标记复核完成");
            }
            feedback.setReviewStatus("REVIEWED");
            return feedback;
        });
        auditService.publish(AuditActions.FEEDBACK_REVIEWED, "FEEDBACK", view.id(), reviewer,
                "复核完成；复核本身不触发任何自动改写（ADR 0039）");
        return view;
    }

    private FeedbackView transition(String id, UnaryOperator<Feedback> change) {
        return feedbackRepository.findById(id)
                .map(change)
                .map(feedbackRepository::save)
                .map(this::toView)
                .orElseThrow(() -> new IllegalArgumentException("feedback not found: " + id));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private FeedbackView toView(Feedback feedback) {
        return new FeedbackView(feedback.getId(), feedback.getVerdict(), feedback.getReason(), feedback.getSignals(),
                feedback.getRuleIds(), feedback.getTicketId(), feedback.getReviewStatus(), feedback.getCreatedAt());
    }

    public record FeedbackView(String id, String verdict, String reason, String signals, String ruleIds,
                               String ticketId, String reviewStatus, Instant createdAt) {
    }
}
