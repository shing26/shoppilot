package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.view.ToolStatus;

/** SSE 事件出口。状态转移即事件，不手写推送逻辑（ADR 0008）。 */
public interface EventSink {

    EventSink NOOP = new EventSink() {
        @Override
        public void status(AgentState state, String detail) {
        }

        @Override
        public void meta(String conversationId, Intent intent, CacheService.Layer cacheLayer) {
        }

        @Override
        public void fallback(FallbackReason reason, String ticketId) {
        }

        @Override
        public void toolExecuting(ToolName tool, String label) {
        }

        @Override
        public void toolResult(ToolName tool, ToolStatus status, String summary) {
        }

        @Override
        public void slotAsk(String slot, String question) {
        }

        @Override
        public void token(String delta) {
        }
    };

    void status(AgentState state, String detail);

    /** 意图与缓存落点确定后、正文开始之前推送，字段集合与 PLAN.md 的 meta 事件一致。 */
    void meta(String conversationId, Intent intent, CacheService.Layer cacheLayer);

    void fallback(FallbackReason reason, String ticketId);

    void toolExecuting(ToolName tool, String label);

    void toolResult(ToolName tool, ToolStatus status, String summary);

    void slotAsk(String slot, String question);

    void token(String delta);
}
