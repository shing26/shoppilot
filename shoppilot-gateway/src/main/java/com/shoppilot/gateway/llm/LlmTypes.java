package com.shoppilot.gateway.llm;

import java.util.List;
import java.util.Map;

/** LLM 抽象的输入输出形状。三态模式共用同一套类型（ADR 0001）。 */
public final class LlmTypes {

    private LlmTypes() {
    }

    /**
     * 一条对话消息。
     *
     * @param toolCallId 仅 role=tool 时有值，指回模型发起的那次调用
     * @param toolCalls  仅 role=assistant 且模型发起了工具调用时有值
     */
    public record Message(String role, String content, String toolCallId, List<ToolCall> toolCalls) {

        public static Message system(String content) {
            return new Message("system", content, null, List.of());
        }

        public static Message user(String content) {
            return new Message("user", content, null, List.of());
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls == null ? List.of() : toolCalls);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, toolCallId, List.of());
        }
    }

    public record ToolCall(String id, String name, Map<String, Object> arguments) {
    }

    public record Request(List<Message> messages, List<Map<String, Object>> tools, double temperature) {
    }

    public record Reply(String content, List<ToolCall> toolCalls, int promptTokens, int completionTokens, String raw) {

        public static Reply text(String content) {
            return new Reply(content, List.of(), 0, 0, null);
        }

        public boolean wantsTool() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}
