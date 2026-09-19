package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.tool.Intent;

import java.util.List;

/**
 * 一次编排的结果。同步端点直接序列化它，流式端点边跑边推。
 *
 * @param trace 每一步状态转移，落盘后可复盘"这个答案是怎么来的"
 */
public record AgentResult(String answer, Intent intent, String triageLayer, CacheService.Layer cacheLayer,
                          List<String> citations, List<TraceStep> trace, FallbackReason fallbackReason,
                          String ticketId, boolean slotAsked, int promptTokens, int completionTokens,
                          boolean toolUsed, boolean degraded, String promptVersion) {

    public record TraceStep(String state, long atMillis, String detail) {
    }
}
