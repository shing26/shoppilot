package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.llm.LlmTypes;

/**
 * 本请求已经发生的 LLM 用量（round35 票 145 / R12 登记债的触发条件）。
 *
 * <p>响应里的 token 字段自此是**全部调用之和**：入口层情绪分类那一跳、规划各轮、预算检查、
 * 收尾那一跳，以及降级 / 受理 / 回放出口已经花掉的那些轮次。此前三处缺口让这个字段低报
 * （预算检查分支只报最后一跳、fallback 与受理报 0、缓存命中报 0 而入口层照付）——
 * 票 140 实测 4 轮用例上报 2,811 vs 计费 11,338 就是它。
 *
 * <p>它只服务响应字段，**不是计费口径**：计费读 {@code shoppilot_llm_tokens_total} 或日预算计数
 * （评测的成本列也照此读，见 part10 判据）。
 */
final class TokenUsage {

    private int promptTokens;
    private int completionTokens;

    TokenUsage(int promptTokens, int completionTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    void add(LlmTypes.Reply reply) {
        if (reply == null) {
            return;
        }
        promptTokens += reply.promptTokens();
        completionTokens += reply.completionTokens();
    }

    int prompt() {
        return promptTokens;
    }

    int completion() {
        return completionTokens;
    }
}
