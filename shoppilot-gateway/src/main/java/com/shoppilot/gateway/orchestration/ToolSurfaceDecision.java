package com.shoppilot.gateway.orchestration;

import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.tool.Intent;

import java.util.List;
import java.util.Map;

/**
 * 角色工具面的可解释判定（ADR 0072 H3）。
 *
 * <p>它只描述“为什么这个角色这一轮看到/看不到工具候选”；不是授权结论。
 * 真正的执行鉴权、幂等、HITL 与审计仍在 {@code ToolDispatcher}。
 */
public record ToolSurfaceDecision(AgentRole role, Domain domain, Intent intent,
                                  List<Map<String, Object>> tools, String reason) {

    public boolean allowed() {
        return tools != null && !tools.isEmpty();
    }

    public static ToolSurfaceDecision of(AgentRole role, Domain domain, Intent intent,
                                         List<Map<String, Object>> tools, String reason) {
        return new ToolSurfaceDecision(role, domain, intent,
                tools == null ? List.of() : List.copyOf(tools), reason);
    }
}
