package com.shoppilot.bizmock.service;

import com.shoppilot.bizmock.domain.Feedback;
import com.shoppilot.bizmock.domain.Ticket;
import com.shoppilot.bizmock.domain.TicketSource;
import com.shoppilot.bizmock.repo.FeedbackRepository;
import com.shoppilot.bizmock.repo.TicketRepository;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
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

    private final FeedbackRepository feedbackRepository;
    private final TicketRepository ticketRepository;

    public FeedbackService(FeedbackRepository feedbackRepository, TicketRepository ticketRepository) {
        this.feedbackRepository = feedbackRepository;
        this.ticketRepository = ticketRepository;
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
     * 点踩进复核队列时同时开一张复核工单（round23 票 69 / ADR 0055）。
     *
     * <p>它和会话里那张降级单是**两件事**：降级单结的是「这次对话办不了」，复核单结的是
     * 「这句答案的内容对不对」。所以不覆盖 {@code feedback.ticketId}（那列指回升级单），
     * 复核单的 id 走 payload 指回本条 feedback。
     *
     * <p>UP 不开单：只计不审，没有人工要处理的事。
     */
    private void openReviewWorkItem(Feedback feedback, String reason) {
        ticketRepository.save(new Ticket(Ticket.nextId(Instant.now()), TenantContextHolder.tenantId(),
                feedback.getCustomerId(), "FEEDBACK_REVIEW",
                truncate("会话 " + feedback.getConversationId() + " 的答复待复核", 512),
                reason == null ? "" : truncate(reason, 8000), "OPEN", Instant.now(), null,
                TicketSource.FEEDBACK_REVIEW, null, null, null,
                WorkItemPayload.of("feedbackId", feedback.getId(),
                        "conversationId", feedback.getConversationId())));
    }

    /** ingest 待复核队列：本店范围内 PENDING 状态的反馈，按时间倒序。 */
    @Transactional(readOnly = true)
    public List<FeedbackView> reviewQueue() {
        return feedbackRepository.findByReviewStatusOrderByCreatedAtDesc("PENDING").stream()
                .map(this::toView).toList();
    }

    /** 人工复核完成：PENDING → REVIEWED。复核本身不触发任何自动改写（ADR 0039）。 */
    @Transactional
    public FeedbackView markReviewed(String id) {
        return transition(id, feedback -> {
            if (!"PENDING".equals(feedback.getReviewStatus())) {
                throw new IllegalStateException("feedback " + id + " 不在 PENDING 状态，不能标记复核完成");
            }
            feedback.setReviewStatus("REVIEWED");
            return feedback;
        });
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
