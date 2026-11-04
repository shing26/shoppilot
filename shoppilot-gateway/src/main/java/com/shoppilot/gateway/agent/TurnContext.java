package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.gateway.orchestration.AgentRole;
import com.shoppilot.gateway.style.StyleService;
import com.shoppilot.gateway.triage.TriageResult;

import java.util.List;

/**
 * 一次请求在编排阶段共享的只读坐标（round48 票 203）。
 *
 * <p>只装 handler 需要的身份、会话、路由与可观测出口；不藏可变状态——工具循环自己的账
 * （rounds / planSteps / messages）留在 {@link PlanExecuteHandler} 内。
 */
record TurnContext(String query, String idempotencyToken, String tenantId, String customerId,
                   String conversationId, SessionStore.Session session, long epoch, Domain domain,
                   TriageResult triage, AgentRole route, List<AgentResult.TraceStep> trace,
                   EventSink sink, String systemPrompt, StyleService.Tier styleTier,
                   TokenUsage usage, CacheService.Lookup lookup) {
}
