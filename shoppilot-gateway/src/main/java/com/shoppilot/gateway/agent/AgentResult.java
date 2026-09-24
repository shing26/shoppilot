package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.tool.Intent;

import java.util.List;
import java.util.Map;

/**
 * 一次编排的结果。同步端点直接序列化它，流式端点边跑边推。
 *
 * @param trace 每一步状态转移，落盘后可复盘"这个答案是怎么来的"
 * @param plan 计划各步的**执行事实**（票 48）；没走工具循环时是空列表，不是 null
 * @param context 这次回答用了什么上下文（票 49）；纯观测，不参与任何判定
 */
public record AgentResult(String answer, Intent intent, String triageLayer, CacheService.Layer cacheLayer,
                          List<String> citations, List<TraceStep> trace, FallbackReason fallbackReason,
                          String ticketId, boolean slotAsked, int promptTokens, int completionTokens,
                          boolean toolUsed, boolean degraded, String promptVersion,
                          List<PlanStep> plan, ContextComposition context) {

    public record TraceStep(String state, long atMillis, String detail) {
    }

    /**
     * 计划里的一步（票 48）。
     *
     * <p>ADR 0036 的**执行语义一字未动**——仍是有序步骤 ≤ 2、前步失败即中止整条 Plan、串行不并行、
     * 不重排。本记录只把原本仅存在于日志 {@code step(...)} 行里的执行事实变成可输出的字段。
     *
     * <p>记的是**真的执行过**的步。被拦下的步（编造单号、缺槽位、参数表达式拒收、模型编出不存在的
     * 工具）不进这里——它们各自有更准确的出口：{@code trace} 里的拦截行与 {@code fallbackReason}。
     */
    public record PlanStep(String tool, String status, long latencyMillis, Map<String, Object> arguments) {
    }

    /**
     * 一次回答的上下文组成（票 49）：注入了哪些规则块、几轮历史、估算多少 prompt token。
     *
     * <p>纯观测——{@code composeUserMessage} 产出的 Prompt 文本不因本记录改变一个字节，用例钉着这条。
     *
     * @param ruleIds 注入 Prompt 的规则块编号，顺序即 Prompt 里的编号顺序；与 {@code citations} 同源
     * @param historyTurns 注入的历史**用户轮数**（不是消息条数）；与 {@code agent.history-turns} 配置同源
     * @param estimatedPromptTokens 组装后 Prompt 的估算 token（按字符数粗估，非模型回报的真值）
     */
    public record ContextComposition(List<String> ruleIds, int historyTurns, int estimatedPromptTokens) {

        public static final ContextComposition NONE = new ContextComposition(List.of(), 0, 0);
    }
}
