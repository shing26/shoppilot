package com.shoppilot.gateway.llm;

import java.util.function.Consumer;

/**
 * 三态 LLM 依赖的统一出口（ADR 0001）。
 *
 * <p>刻意不引入厂商 SDK：三家供应商都是 OpenAI 兼容协议，换供应商只改 base-url 与 model。
 */
public interface LlmClient {

    /** 非流式：用于带 tools 的规划轮，避免解析分片的 tool_calls 增量。 */
    LlmTypes.Reply complete(LlmTypes.Request request);

    /** 流式：用于最终答案轮，tokenSink 收到每个增量文本。 */
    LlmTypes.Reply stream(LlmTypes.Request request, Consumer<String> tokenSink);

    String mode();
}
