package com.shoppilot.gateway.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handoff 的一等契约（ADR 0072）。它是“专家之间必须带走什么”的声明面；
 * 第一阶段先落契约与序列化，运行时可以在没有完整 Handoff 的请求里安全地构造/传输它。
 *
 * <p>硬约束：已确认槽位不得因 Handoff 丢失；trail 与自由文本出运行时前要裁剪/脱敏。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HandoffContext(
        String conversationId,
        AgentRole from,
        AgentRole to,
        String userUtterance,
        String normalizedIntent,
        Map<String, String> confirmedSlots,
        List<ToolExecutionRecord> executedTools,
        List<ToolExecutionRecord> failedTools,
        PendingState pendingState,
        List<HandoffStep> handoffTrail,
        String promptVersion,
        String policyVersion
) {

    private static final int MAX_TRAIL = 5;
    private static final int MAX_TEXT = 512;

    public HandoffContext {
        confirmedSlots = confirmedSlots == null ? Map.of() : Map.copyOf(confirmedSlots);
        executedTools = executedTools == null ? List.of() : List.copyOf(executedTools);
        failedTools = failedTools == null ? List.of() : List.copyOf(failedTools);
        pendingState = pendingState == null ? PendingState.none() : pendingState;
        handoffTrail = handoffTrail == null ? List.of() : List.copyOf(handoffTrail);
        userUtterance = truncate(userUtterance);
        normalizedIntent = truncate(normalizedIntent);
    }

    /** 工单 payload 的受控投影：只带事实，裁长度，不展开工具参数。 */
    public Map<String, Object> toTicketPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("route", from + "->" + to);
        payload.put("normalizedIntent", normalizedIntent);
        payload.put("confirmedSlots", confirmedSlots);
        payload.put("executedToolCount", executedTools.size());
        payload.put("failedToolCount", failedTools.size());
        payload.put("pending", pendingState.pending());
        payload.put("handoffTrail", handoffTrail.stream().limit(MAX_TRAIL).toList());
        payload.put("promptVersion", promptVersion);
        payload.put("policyVersion", policyVersion);
        return payload;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_TEXT) {
            return value;
        }
        return value.substring(0, MAX_TEXT);
    }
}
