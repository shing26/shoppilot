package com.shoppilot.bizmock.refundoutbox;

import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.bizmock.workitem.WorkItemClient;
import com.shoppilot.tool.workitem.TicketSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 退款审批工单补偿器（round63 票 234 / U3）。
 *
 * <p>边界：受理与工单在两个进程，不可能同事务。能保证的是**本地意图不丢**、
 * 失败可重试、成功后回填；工单创建仍是至少一次，SENDING 租约过期后的重试可能
 * 重复开单，这个窗口必须如实登记，不能宣称恰好一次。
 */
@Component
public class RefundWorkItemProcessor {

    private static final Logger log = LoggerFactory.getLogger(RefundWorkItemProcessor.class);
    private static final String PENDING_REVIEW = "PENDING_REVIEW";
    private static final int BATCH = 20;
    private static final Duration SENDING_LEASE = Duration.ofMinutes(2);
    /**
     * 成功补偿计数名。**不要带 `_created`**：Micrometer/Prometheus 把它当保留后缀剥掉，
     * 抓取端会变成另一个名字，按文档配的看板永远为空。
     */
    public static final String COMPENSATED_METRIC = "shoppilot_refund_ticket_outbox_compensated_total";

    private final RefundWorkItemOutboxRepository outbox;
    private final WorkItemClient workItemClient;
    private final TransactionTemplate transactionTemplate;
    private final Counter missing;
    private final Counter created;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    public RefundWorkItemProcessor(RefundWorkItemOutboxRepository outbox,
                                   WorkItemClient workItemClient,
                                   TransactionTemplate transactionTemplate,
                                   MeterRegistry registry,
                                   @Value("${shoppilot.bizmock.refund-outbox.initial-backoff:2s}") Duration initialBackoff,
                                   @Value("${shoppilot.bizmock.refund-outbox.max-backoff:5m}") Duration maxBackoff) {
        this.outbox = outbox;
        this.workItemClient = workItemClient;
        this.transactionTemplate = transactionTemplate;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.missing = Counter.builder("shoppilot_refund_ticket_missing_total")
                .description("退款已落库但审批工单没开成、等待补偿的次数")
                .register(registry);
        // 名字里不能出现 Micrometer/Prometheus 的保留后缀 `_created`：它会在抓取端被剥掉，
        // 文档写的名字就永远抓不到。`_total` 是 counter 的正常后缀，导出时会被保留。
        this.created = Counter.builder(COMPENSATED_METRIC)
                .description("退款审批工单由 outbox 补偿器补开的次数")
                .register(registry);
        Gauge.builder("shoppilot_refund_ticket_outbox_pending", outbox,
                        RefundWorkItemOutboxRepository::pendingCount)
                .description("尚未成功开单或取消的退款审批 outbox 行数")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${shoppilot.bizmock.refund-outbox.poll-interval-ms:5000}",
            initialDelayString = "${shoppilot.bizmock.refund-outbox.initial-delay-ms:5000}")
    public void poll() {
        processDue(Instant.now());
    }

    /**
     * 受理提交后的立即尝试。返回新工单号；失败返回空并把行留给补偿器。
     * 不抛异常——退款已经提交，不能让开单失败把接口变成 500。
     */
    public Optional<String> processRefund(long refundId, String tenantId, Instant now) {
        return outbox.findDueByRefundId(refundId, tenantId, now)
                .filter(row -> outbox.claim(row.id(), now))
                .flatMap(row -> processRow(row, now));
    }

    /** 补偿一轮；返回本轮成功开单数。调度器与测试共用此入口。 */
    public int processDue(Instant now) {
        outbox.reclaimStale(now.minus(SENDING_LEASE), now);
        List<RefundWorkItemOutboxRepository.Row> due = outbox.findDue(now, BATCH);
        int sent = 0;
        for (RefundWorkItemOutboxRepository.Row row : due) {
            if (outbox.claim(row.id(), now) && processRow(row, now).isPresent()) {
                sent++;
            }
        }
        return sent;
    }

    private Optional<String> processRow(RefundWorkItemOutboxRepository.Row row, Instant now) {
        // 补偿器既可能跑在调度线程（无上下文），也可能在受理请求里被立即调用（有上下文）。
        // 后一种情况必须恢复原上下文，而不是把调用方的租户/买家一起清掉。
        String previousTenant = TenantContextHolder.tenantIdOrNull();
        String previousCustomer = TenantContextHolder.customerId();
        try {
            TenantContextHolder.set(row.tenantId(), row.customerId());
            Optional<String> refundStatus = outbox.refundStatus(row.refundId(), row.tenantId());
            if (refundStatus.isEmpty() || !PENDING_REVIEW.equals(refundStatus.get())) {
                if (!outbox.cancelClaimed(row.id(), row.tenantId(), now)) {
                    log.warn("退款 {} 的 outbox 行 {} 已不在 SENDING，无法由补偿器取消（可能已被审核取消）",
                            row.refundId(), row.id());
                }
                return Optional.empty();
            }

            String ticketId = workItemClient.create(TicketSource.REFUND_APPROVAL, row.customerId(),
                    "REFUND_APPROVAL", "订单 " + row.orderId() + " 的退款待人工审核", "", null,
                    "{\"refundId\":\"" + row.refundId() + "\",\"orderId\":\"" + row.orderId() + "\"}");
            if (ticketId == null || ticketId.isBlank()) {
                retryLater(row, now, "工单服务未返回工单号");
                return Optional.empty();
            }

            transactionTemplate.executeWithoutResult(status -> {
                outbox.attachTicketToRefund(row.refundId(), row.tenantId(), ticketId);
                outbox.markSent(row.id(), row.tenantId(), ticketId, now);
            });
            created.increment();
            return Optional.of(ticketId);
        } catch (RuntimeException failure) {
            retryLater(row, now, failure.getMessage());
            return Optional.empty();
        } finally {
            if (previousTenant == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.set(previousTenant, previousCustomer);
            }
        }
    }

    private void retryLater(RefundWorkItemOutboxRepository.Row row, Instant now, String error) {
        int attempts = row.attempts() + 1;
        Duration delay = backoff(attempts);
        outbox.markRetry(row.id(), row.tenantId(), attempts, now.plus(delay), error, now);
        missing.increment();
        log.warn("退款 {} 的审批工单补偿失败，第 {} 次，{} 后重试: {}",
                row.refundId(), attempts, delay, error);
    }

    private Duration backoff(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        Duration delay = initialBackoff.multipliedBy(multiplier);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
