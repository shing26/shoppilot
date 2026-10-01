package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.domain.RoutingRule;
import com.shoppilot.bizmock.domain.TicketPriority;
import com.shoppilot.bizmock.repo.RoutingRuleRepository;
import com.shoppilot.bizmock.service.RoutingService;
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
 * 分流规则表（round23 票 70/71 / ADR 0055、0056）。
 *
 * <p>票 70 时它是**只读**的：规则能改但查不到谁改的，比不能改更糟。票 71 的审计事件落地后
 * 写入口才开——每一次写都带一条 {@code audit} 事件，所以「这张单当初去了哪个队列」这件事
 * 现在能一路回溯到规则变更。
 *
 * <p>只允许**新增**与**启停**，不允许改匹配键与队列：改匹配键会让历史分派再也复算不出来。
 * {@code X-Reviewer} 是调用方自报的（身份域是下一轮，ADR 0056）：进审计，不进权限。
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

    /** 全量规则表（含未启用行），按 id 升序——读的人要能自己判断哪条更具体。 */
    @GetMapping
    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return ruleRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(RoutingRuleController::toView)
                .toList();
    }

    /** 新增规则。租户与匹配键用 {@code *} 表示通配。 */
    @PostMapping
    @Transactional
    public ResponseEntity<RuleView> create(@RequestBody RuleRequest request,
                                            @RequestHeader(value = "X-Reviewer", required = false) String reviewer) {
        try {
            RoutingRule saved = routingService.createRule(
                    request.tenantId(), request.source(), request.reason(), request.queue(),
                    TicketPriority.of(request.priority()), request.slaMinutes(),
                    !Boolean.FALSE.equals(request.enabled()), reviewer);
            return ResponseEntity.status(HttpStatus.CREATED).body(toView(saved));
        } catch (IllegalArgumentException badRequest) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** 启停规则。这是唯一的原地修改：匹配键与队列一旦落库就不再改。 */
    @PatchMapping("/{ruleId}/enabled")
    @Transactional
    public ResponseEntity<RuleView> setEnabled(@PathVariable Long ruleId,
                                               @RequestBody EnableRequest request,
                                               @RequestHeader(value = "X-Reviewer", required = false) String reviewer) {
        try {
            RoutingRule saved = routingService.setRuleEnabled(ruleId, Boolean.TRUE.equals(request.enabled()), reviewer);
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