package com.shoppilot.gateway.llm;

import java.util.List;
import java.util.Map;

/**
 * 一张录放卡（ADR 0083）。文件放在 {@code <dir>/<model>/<promptVersion>/<key>.json}。
 *
 * @param key           {@link CassetteKey} 计算出的 sha256；与文件名同值，防止改名绕过
 * @param model         实际调用的模型名（dev = {@code qwen-plus}；local = Ollama 模型名）
 * @param promptVersion 录制时的 {@code PromptCatalog.version()}；提示词换代后旧卡自然失效
 * @param recordedAt    ISO-8601 录制时间（证据用，不参与 key）
 * @param source        {@code dev} / {@code local}；人工写入的来源必须如实，Mock 不能冒充
 * @param request       请求摘要；回放前可用它重算 key 做一致性校验
 * @param response      模型响应；回放与录制时的工具选择、答案、token 数逐字段一致
 */
public record LlmCassette(
        String key,
        String model,
        String promptVersion,
        String recordedAt,
        String source,
        Request request,
        Response response) {

    public record Request(List<LlmTypes.Message> messages,
                          List<Map<String, Object>> tools,
                          double temperature,
                          int maxTokens) {
    }

    public record Response(String content,
                           List<LlmTypes.ToolCall> toolCalls,
                           int promptTokens,
                           int completionTokens) {

        public LlmTypes.Reply toReply() {
            return new LlmTypes.Reply(content, toolCalls == null ? List.of() : toolCalls,
                    promptTokens, completionTokens, null);
        }
    }
}
