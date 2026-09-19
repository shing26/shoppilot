package com.shoppilot.bizmock.service;

import com.shoppilot.bizmock.domain.Feedback;
import com.shoppilot.bizmock.repo.FeedbackRepository;
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

    public FeedbackService(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
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
        return toView(feedbackRepository.save(feedback));
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
