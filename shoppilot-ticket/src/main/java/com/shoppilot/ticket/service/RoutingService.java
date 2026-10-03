package com.shoppilot.ticket.service;

import com.shoppilot.ticket.audit.AuditPublisher;
import com.shoppilot.ticket.domain.RoutingRule;
import com.shoppilot.ticket.domain.Ticket;
import com.shoppilot.ticket.domain.TicketPriority;
import com.shoppilot.ticket.domain.TicketStatus;
import com.shoppilot.ticket.repo.RoutingRuleRepository;
import com.shoppilot.ticket.repo.TicketRepository;
import com.shoppilot.tool.audit.Actor;
import com.shoppilot.tool.audit.AuditActions;
import com.shoppilot.tool.workitem.TicketSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分流与 SLA 计时（round23 票 70 / ADR 0055）。
 *
 * <p>分派是**确定性**的：读规则表 → 挑最具体的一条 → 定队列、定优先级、定 SLA 截止。
 * 规则表里没有可用规则时走代码默认队列，而不是报错——一张工单因为配置缺失而没有去向，
 * 比落到默认队列糟得多。
 *
 * <p>坐席模型是 pull（ADR 0055）：本服务**不派单**、不抢单、不知道谁在线。
 */
@Service
public class RoutingService {

    /** 规则表全空/全禁用时的最终兜底。规则表里的通配行通常先命中，这里是最后一道。 */
    public static final String DEFAULT_QUEUE = "DEFAULT";
    public static final int DEFAULT_SLA_MINUTES = 24 * 60;

    private final RoutingRuleRepository ruleRepository;
    private final TicketRepository ticketRepository;
    private final AuditPublisher auditPublisher;

    public RoutingService(RoutingRuleRepository ruleRepository, TicketRepository ticketRepository,
                          AuditPublisher auditPublisher) {
        this.ruleRepository = ruleRepository;
        this.ticketRepository = ticketRepository;
        this.auditPublisher = auditPublisher;
    }

    /** 分派结果：队列 + 优先级 + SLA 截止。 */
    public record Assignment(String queue, TicketPriority priority, Instant slaDeadline) {
    }

    /**
     * 给一张刚建出来的工单定去向。三件事一次做完，因为它们出自同一条规则行：
     * 队列决定谁处理它，优先级决定同队列里的先后，SLA 决定它多久算超时。
     */
    public Assignment assign(String tenantId, TicketSource source, String reason, String rawPriority) {
        TicketPriority baseline = baselinePriority(source, rawPriority);
        Optional<RoutingRule> matched = match(tenantId, source, reason);

        String queue = matched.map(RoutingRule::getQueue).orElse(DEFAULT_QUEUE);
        int slaMinutes = matched.map(RoutingRule::getSlaMinutes).orElse(DEFAULT_SLA_MINUTES);
        // 规则只能把工单抬高：情绪升级单与资金动作不能被一条泛化规则压到后面去。
        TicketPriority priority = matched
                .map(rule -> TicketPriority.highest(baseline, rule.getPriority()))
                .orElse(baseline);

        return new Assignment(queue, priority, Instant.now().plus(Duration.ofMinutes(slaMinutes)));
    }

    /**
     * 选最具体的那条规则。同具体度时取 id 小的：分派必须可复现，
     * 否则「这张单为什么去了那个队列」永远答不上来。
     */
    Optional<RoutingRule> match(String tenantId, TicketSource source, String reason) {
        List<RoutingRule> candidates = ruleRepository.findByEnabledTrueOrderByIdAsc().stream()
                .filter(rule -> rule.matches(tenantId, source, reason))
                .toList();
        return candidates.stream()
                .max((a, b) -> {
                    int bySpecificity = Integer.compare(a.specificityFor(tenantId, source, reason),
                            b.specificityFor(tenantId, source, reason));
                    return bySpecificity != 0 ? bySpecificity : Long.compare(b.getId(), a.getId());
                });
    }

    /**
     * 来源与情绪推出的基线优先级，规则表在这之上只能抬高。
     *
     * <p>资金动作的基线来自来源而不是规则表：退款审批单在任何配置下都是资金动作，
     * 让它依赖「有人记得给退款配一条规则」是危险的默认。
     */
    private TicketPriority baselinePriority(TicketSource source, String rawPriority) {
        TicketPriority fromRequest = TicketPriority.of(rawPriority);
        return source == TicketSource.REFUND_APPROVAL
                ? TicketPriority.highest(fromRequest, TicketPriority.MONEY)
                : fromRequest;
    }

    /**
     * SLA 超时打戳（惰性求值，在工单列表读路径上触发）。
     *
     * <p>**只打戳**：不改状态、不关单、不通知任何人（ADR 0055 明确「不承诺解决时限」）。
     * 定时任务那套机械本轮不上——读路径上算一遍就够，少一个线程池少一处停机负担。
     *
     * <p>打戳时刻记的是「观测到它已超时的那一刻」，不倒填截止时间：倒填会让
     * 「超时多久」这个数在两次读之间自相矛盾。
     */
    @Transactional
    public int escalateOverdue(String tenantId, Instant now) {
        List<Ticket> overdue = ticketRepository.findAll().stream()
                .filter(ticket -> tenantId == null || tenantId.equals(ticket.getTenantId()))
                .filter(ticket -> ticket.getEscalatedAt() == null)
                .filter(ticket -> ticket.getSlaDeadline() != null && now.isAfter(ticket.getSlaDeadline()))
                .filter(ticket -> {
                    TicketStatus status = TicketStatus.parse(ticket.getStatus());
                    return status != null && status != TicketStatus.RESOLVED;
                })
                .toList();
        overdue.forEach(ticket -> ticket.setEscalatedAt(now));
        ticketRepository.saveAll(overdue);
        return overdue.size();
    }

    /** 新增一条规则。每次写都发审计事件——票 71 之前规则只能走迁移变更。 */
    @Transactional
    public RoutingRule createRule(String tenantId, String source, String reason, String queue,
                                  TicketPriority priority, int slaMinutes, boolean enabled, Actor reviewer) {
        RoutingRule rule = ruleRepository.save(new RoutingRule(tenantId, source, reason, queue, priority, slaMinutes,
                enabled, Instant.now(), reviewer.name()));
        auditPublisher.publish(AuditActions.ROUTING_RULE_CREATED, "ROUTING_RULE", String.valueOf(rule.getId()), reviewer,
                "新增规则 " + tenantId + "/" + source + "/" + reason + " → " + queue
                        + "（SLA " + slaMinutes + " 分钟，优先级 " + priority.name() + "）");
        return rule;
    }

    /** 启停一条规则。 */
    @Transactional
    public RoutingRule setRuleEnabled(Long id, boolean enabled, Actor reviewer) {
        RoutingRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则 " + id + " 不存在"));
        rule.setEnabled(enabled);
        rule.markUpdated(Instant.now(), reviewer.name());
        RoutingRule saved = ruleRepository.save(rule);
        auditPublisher.publish(AuditActions.ROUTING_RULE_TOGGLED, "ROUTING_RULE", String.valueOf(id), reviewer,
                (enabled ? "启用" : "停用") + " 规则 " + rule.getTenantId() + "/" + rule.getSource() + "/"
                        + rule.getReason() + " → " + rule.getQueue());
        return saved;
    }
}
