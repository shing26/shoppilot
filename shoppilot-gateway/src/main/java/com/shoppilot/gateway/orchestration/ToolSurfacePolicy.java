package com.shoppilot.gateway.orchestration;

import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolContracts;

import java.util.List;
import java.util.Map;

/**
 * 角色工具面的唯一裁剪点（ADR 0072 H3）。
 *
 * <p>它只回答“这个角色这一次能看到哪些工具候选，为什么”；不回答“能不能执行”。
 * 鉴权、幂等、状态校验、HITL 与审计仍全部在 `ToolDispatcher`。
 */
public final class ToolSurfacePolicy {

    private ToolSurfacePolicy() {
    }

    public static List<Map<String, Object>> toolsFor(AgentRole role, Domain domain, Intent intent) {
        return decide(role, domain, intent).tools();
    }

    /** 可解释判定：状态机 trace 与 golden 都用同一份 reason，不另造第二套口径。 */
    public static ToolSurfaceDecision decide(AgentRole role, Domain domain, Intent intent) {
        if (domain != Domain.ECOMMERCE) {
            return ToolSurfaceDecision.of(role, domain, intent, List.of(), "DENIED:DOMAIN_ISOLATION");
        }
        if (role == AgentRole.PRE_SALES) {
            return ToolSurfaceDecision.of(role, domain, intent, List.of(), "DENIED:PRE_SALES_READ_ONLY");
        }
        if (intent == null || intent == Intent.UNKNOWN) {
            return ToolSurfaceDecision.of(role, domain, intent, ToolContracts.actionDescriptors(),
                    "ALLOWED:UNKNOWN_DYNAMIC_SURFACE");
        }
        if (intent.isAction()) {
            return ToolSurfaceDecision.of(role, domain, intent, ToolContracts.actionDescriptors(),
                    "ALLOWED:AFTER_SALES_ACTION_SURFACE");
        }
        return ToolSurfaceDecision.of(role, domain, intent, List.of(), "DENIED:POLICY_CONSULTATION");
    }
}
