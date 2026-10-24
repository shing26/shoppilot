package com.shoppilot.gateway.orchestration;

import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolContracts;

import java.util.List;
import java.util.Map;

/**
 * 角色工具面的唯一裁剪点（ADR 0072）。
 *
 * <p>它只回答“这个角色这一次能看到哪些工具候选”；不回答“能不能执行”。
 * 鉴权、幂等、状态校验、HITL 与审计仍全部在 {@code ToolDispatcher}。
 */
public final class ToolSurfacePolicy {

    private ToolSurfacePolicy() {
    }

    public static List<Map<String, Object>> toolsFor(AgentRole role, Domain domain, Intent intent) {
        if (domain != Domain.ECOMMERCE) {
            return List.of();
        }
        if (role == AgentRole.PRE_SALES) {
            return List.of();
        }
        if (intent == null || intent == Intent.UNKNOWN) {
            return ToolContracts.actionDescriptors();
        }
        return intent.isAction() ? ToolContracts.actionDescriptors() : List.of();
    }
}
