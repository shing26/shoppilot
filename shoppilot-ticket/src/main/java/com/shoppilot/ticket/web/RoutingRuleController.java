package com.shoppilot.ticket.web;

import com.shoppilot.ticket.domain.RoutingRule;
import com.shoppilot.ticket.domain.TicketPriority;
import com.shoppilot.ticket.repo.RoutingRuleRepository;
import com.shoppilot.ticket.service.RoutingService;
import com.shoppilot.tool.audit.ActorHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 分流规则表（round23 票 70/71/72 / ADR 0055、0056）。
 *
 * <p>写入口在票 71 才开：规则能改但查不到谁改的，比不能改更糟。每一次写都带一条审计事件，
 * 所以「这张单当初去了哪个队列」现在能一路回溯到规则变更。
 *
 * <p>只允许**新增**与**启停**；匹配键与队列一旦落库就不再改。
 */
@RestController
@RequestMapping("/api/routing-rules")
public class RoutingRuleController {

    private final RoutingRuleRepository ruleRepository;
    private final RoutingService routingService;

    public RoutingRuleController(RoutingRuleRepository ruleRepository, RoutingService routingService) {
        this.ruleRepository = ruleRepository;
        this.routingService = routingService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return ruleRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(RoutingRuleController::toView)
                .toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<RuleView> create(@RequestBody RuleRequest request,
                                            @RequestHeader(value = ActorHeaders.ACTOR, required = false) String actor,
                                            @RequestHeader(value = ActorHeaders.ACTOR_AUTHENTICATED,
                                                    required = false) String authenticated) {
        try {
            RoutingRule saved = routingService.createRule(
                    request.tenantId(), request.source(), request.reason(), request.queue(),
                    TicketPriority.of(request.priority()), request.slaMinutes(),
                    !Boolean.FALSE.equals(request.enabled()), ActorHeaders.of(actor, authenticated));
            return ResponseEntity.status(HttpStatus.CREATED).body(toView(saved));
        } catch (IllegalArgumentException badRequest) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PatchMapping("/{ruleId}/enabled")
    @Transactional
    public ResponseEntity<RuleView> setEnabled(@PathVariable Long ruleId,
                                               @RequestBody EnableRequest request,
                                               @RequestHeader(value = ActorHeaders.ACTOR, required = false) String actor,
                                               @RequestHeader(value = ActorHeaders.ACTOR_AUTHENTICATED,
                                                       required = false) String authenticated) {
        try {
            RoutingRule saved = routingService.setRuleEnabled(ruleId, Boolean.TRUE.equals(request.enabled()),
                    ActorHeaders.of(actor, authenticated));
            return ResponseEntity.ok(toView(saved));
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.notFound().build();
        }
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

    public record RuleRequest(String tenantId, String source, String reason, String queue, String priority,
                              Integer slaMinutes, Boolean enabled) {
    }

    public record EnableRequest(Boolean enabled) {
    }
}