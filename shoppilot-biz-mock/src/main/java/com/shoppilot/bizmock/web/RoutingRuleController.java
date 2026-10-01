package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.domain.RoutingRule;
import com.shoppilot.bizmock.repo.RoutingRuleRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 分流规则表的**只读**面（round23 票 70 / ADR 0055）。
 *
 * <p>刻意不做写端点：规则改动一旦能从 HTTP 进来，就必须有「谁改的」这一层审计，
 * 而那正是票 71 的事件骨干（{@code audit} topic）要提供的东西。
 * 在它接上之前，规则只能经 Flyway 迁移变更——那一步天生带提交记录。
 * 触发条件 = 票 71 落地。
 */
@RestController
@RequestMapping("/api/routing-rules")
public class RoutingRuleController {

    private final RoutingRuleRepository ruleRepository;

    public RoutingRuleController(RoutingRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    /** 全量规则表（含未启用行），按 id 升序——读的人要能自己判断哪条更具体。 */
    @GetMapping
    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return ruleRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(RoutingRuleController::toView)
                .toList();
    }

    private static RuleView toView(RoutingRule rule) {
        return new RuleView(rule.getId(), rule.getTenantId(), rule.getSource(), rule.getReason(), rule.getQueue(),
                rule.getPriority().name(), rule.getPriority().literal(), rule.getSlaMinutes(), rule.isEnabled(),
                rule.getUpdatedAt(), rule.getUpdatedBy());
    }

    /**
     * @param priorityName 领域侧的枚举名（URGENT_EMOTION / MONEY / NORMAL）
     * @param priorityLiteral 存储与对外的字面量（high / money / normal）——两者都给人看，
     *         免得看的人以为它们是同一个东西的不同写法
     */
    public record RuleView(Long id, String tenantId, String source, String reason, String queue,
                           String priorityName, String priorityLiteral, int slaMinutes, boolean enabled,
                           Instant updatedAt, String updatedBy) {
    }
}