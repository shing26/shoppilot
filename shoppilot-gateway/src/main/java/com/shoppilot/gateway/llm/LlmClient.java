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

    /**
     * 这一路的 token 数是**怎么来的**（round22 票 66 / ADR 0048）：{@code provider} = 供应商回报的真值
     * （云端 {@code usage.prompt_tokens}、Ollama 自报的 {@code prompt_eval_count}）；
     * {@code estimate} = 本地按字符数估算。
     *
     * <p>说出来是为了让口径**机器可读**：{@code shoppilot_llm_tokens_total} 的三个来源计量方法不同
     * 却共用一个指标名。模式在部署期固定，所以同一条序列里不会混方法 —— 缺的只是这层声明。
     * 加的是**标签**不是新指标名（指标名计数不变）。
     */
    default String tokenSource() {
        return "provider";
    }
}
