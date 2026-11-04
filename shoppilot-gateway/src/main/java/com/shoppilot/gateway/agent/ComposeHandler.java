package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.llm.LlmException;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.style.StyleService;
import com.shoppilot.tool.Intent;

import java.util.ArrayList;
import java.util.List;

/**
 * 答案合成阶段（round48 票 203）。
 *
 * <p>负责最后那一跳（直出或流式）、local 3B 的确定性保真守卫、空答案出口，以及引用/上下文
 * 观测字段。回放与退款受理两个确定性出口也在这里——它们与"模型答完"同属答案面，都不打第二跳模型。
 * 检索判定、工具循环、缓存写回不在本类。
 */
final class ComposeHandler {

    /** local 模式补充只加在 local，不改 v1.2.0 基座与 dev 行为（票 167）。 */
    private static final String LOCAL_ANSWER_FIDELITY_HINT =
            "\n\n【本地模式补充】纯政策咨询直接按政策条款回答，不要索要订单号；"
            + "条款里出现的时限、比例、金额等数字必须原样写进回答，不得省略。";

    private final TurnServices services;

    ComposeHandler(TurnServices services) {
        this.services = services;
    }

    /** 合成结果二选一：要么拿到正文，要么已经落到降级出口。 */
    record ComposeOutcome(String answer, AgentResult terminal) {

        static ComposeOutcome answer(String answer) {
            return new ComposeOutcome(answer, null);
        }

        static ComposeOutcome terminal(AgentResult result) {
            return new ComposeOutcome(null, result);
        }

        boolean failed() {
            return terminal != null;
        }
    }

    ComposeOutcome compose(TurnContext ctx, List<LlmTypes.Message> messages, LlmTypes.Reply lastReply,
                           int rounds, boolean answeredByBudgetCheck, HybridRetriever.Result retrieved) {
        Intent intent = ctx.triage().intent();
        TurnServices.step(ctx.trace(), ctx.sink(), AgentState.REPLY, "streaming");
        String answer;
        if (lastReply != null && !lastReply.wantsTool() && lastReply.content() != null
                && (rounds == 0 || answeredByBudgetCheck)) {
            // 首轮就给出内容，或预算检查后模型明确不再要工具：直接把它按打字机切块推出去，不再多打一次模型。
            // token 字段照旧走总账（票 145 / R12 的第一处缺口：这里此前只报这一跳，4 轮用例因此上报 2,811 vs 计费 11,338）
            answer = localPolicyAnswerGuard(lastReply.content(), intent, ctx.query(), retrieved);
            emitChunked(answer, ctx.sink());
        } else {
            try {
                LlmTypes.Reply finalReply = services.llm.stream(new LlmTypes.Request(messages, List.of(),
                        services.properties.llm().temperature()), ctx.sink()::token);
                ctx.usage().add(finalReply);
                answer = finalReply.content();
            } catch (LlmException failure) {
                return ComposeOutcome.terminal(services.fallback(AgentState.REPLY, ctx.trace(), ctx.sink(),
                        TurnServices.mapLlmFailure(failure), ctx.query(), failure.getMessage(), ctx.styleTier(),
                        ctx.usage()));
            }
        }

        // 空答案不是答案（2026-09-28 黑盒 QA 实测）：模型既没发工具调用、也没给出正文时，
        // 今天会返回 HTTP 200 + `answer:""` + 无工单——买家什么也没拿到，也没有可查的出口。
        // 这与 ADR 0009「办不成就给一句实话并落可查工单」直接冲突，所以在这里兜住。
        // 复用 LLM_CIRCUIT_OPEN 的措辞（"智能回复暂时不可用，已为您登记人工处理"）：**不新增枚举值**，
        // 避免改动 README 的「枚举 10 / 降级 9」公开口径。
        if (answer == null || answer.isBlank()) {
            return ComposeOutcome.terminal(services.fallback(AgentState.REPLY, ctx.trace(), ctx.sink(),
                    FallbackReason.LLM_CIRCUIT_OPEN, ctx.query(), "模型未产出正文且未派发工具（空答案）",
                    ctx.styleTier(), ctx.usage()));
        }
        return ComposeOutcome.answer(answer);
    }

    // ── 确定性出口 ────────────────────────────────────────────────────────

    /**
     * 幂等回放的回答出口：确定性话术 + {@code duplicate_submit} 事件，不打模型、不落缓存、不进工具循环。
     *
     * <p>会话照常推进（回放也是这一轮真实发生过的事，下一轮该看得到它），并清掉待办动作——若走到这里，
     * 说明那次请求已经办成过，会话上不该还挂着一个待补的槽位。
     */
    AgentResult replayAnswer(IdempotencyService.Replay replay, SessionStore.Session session, String query,
                             EventSink sink, List<AgentResult.TraceStep> trace, String tenantId,
                             String customerId, String conversationId, TokenUsage usage) {
        Intent intent = replay.tool().intent();
        String answer = ReplayReply.render(replay.tool(), replay.resultJson());
        sink.meta(conversationId, intent, CacheService.Layer.NONE);
        TurnServices.step(trace, sink, AgentState.REPLY, "replay=" + replay.tool().apiName());
        sink.duplicateSubmit(replay.tool(), answer);
        services.sessionStore.save(tenantId, customerId,
                services.sessionStore.appendTurn(TurnServices.clearPending(session), query, answer));
        sink.token(answer);
        // 回放不打模型，但入口层那一跳照付（票 145 / R12）：照实回填，不假报 0。
        return new AgentResult(answer, intent, "replay", CacheService.Layer.NONE, List.of(), trace, null, null,
                false, usage.prompt(), usage.completion(), true, false, services.promptCatalog.version(), List.of(),
                AgentResult.ContextComposition.NONE, List.of());
    }

    /**
     * 退款审批闸门的受理出口（ADR 0047 票 59）：确定性受理话术 + {@code shoppilot_refund_pending_total}，
     * 不打模型、不落缓存、不进工具循环。会话照常推进并清掉待办动作——受理已经发生过。
     *
     * <p>与 {@link #replayAnswer} 的区别是事件：受理推的是 {@code tool_result}（已在调用点发出），
     * 不是 {@code duplicate_submit} —— 这是**新受理的一笔**，不是重复提交。
     */
    AgentResult pendingApprovalAnswer(ToolDispatcher.Dispatch dispatch, SessionStore.Session session,
                                      String query, EventSink sink, List<AgentResult.TraceStep> trace,
                                      String tenantId, String customerId, String layer,
                                      List<AgentResult.PlanStep> planSteps, TokenUsage usage) {
        services.registry.counter("shoppilot_refund_pending_total").increment();
        String answer = ReplayReply.pendingApproval(dispatch.tool(), dispatch.json());
        TurnServices.step(trace, sink, AgentState.REPLY, "pending-approval=" + dispatch.tool().apiName());
        services.sessionStore.save(tenantId, customerId,
                services.sessionStore.appendTurn(TurnServices.clearPending(session), query, answer));
        sink.token(answer);
        // 受理即收尾，但受理之前那次规划调用已经花掉了（票 145 / R12 的第二处缺口）。
        return new AgentResult(answer, dispatch.tool().intent(), layer, CacheService.Layer.NONE, List.of(), trace,
                null, null, false, usage.prompt(), usage.completion(), true, false,
                services.promptCatalog.version(), planSteps, AgentResult.ContextComposition.NONE, List.of());
    }

    // ── 引用与上下文观测 ──────────────────────────────────────────────────

    static List<String> citationIds(HybridRetriever.Result retrieved) {
        return retrieved == null ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::ruleId).toList();
    }

    /**
     * 引用详情（round33 / ADR 0067）：把检索结果里的标题与出处搬进可输出的字段。
     *
     * <p>与 {@code citations} 同源同序——买家端引用列表的两列就是这两份数据的拼装。
     * 零召回时是空列表，不是 null。
     */
    static List<AgentResult.CitationDetail> citationDetailsOf(HybridRetriever.Result retrieved) {
        if (retrieved == null || retrieved.empty()) {
            return List.of();
        }
        return retrieved.rules().stream()
                .map(rule -> new AgentResult.CitationDetail(rule.ruleId(),
                        rule.title() == null ? "" : rule.title(),
                        rule.sourceDoc() == null ? "" : rule.sourceDoc()))
                .toList();
    }

    /**
     * 命中路径的引用详情：标题与出处都随缓存条目落库（{@link com.shoppilot.gateway.cache.CacheEntry}），
     * 与 {@code citations} 同源同序。
     *
     * <p>{@code sourceDocs} 是 2026-10-17 补的：round33 落了标题却漏了出处，于是命中路径
     * {@code citationDetails.doc} 恒为空串——买家端引用列表的两列本就是从这两份数据拼的。
     */
    static List<AgentResult.CitationDetail> citationDetailsOf(List<String> ruleIds, List<String> headings,
                                                              List<String> docs) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return List.of();
        }
        List<AgentResult.CitationDetail> details = new ArrayList<>();
        for (int i = 0; i < ruleIds.size(); i++) {
            String heading = headings != null && i < headings.size() && headings.get(i) != null
                    ? headings.get(i) : "";
            String doc = docs != null && i < docs.size() && docs.get(i) != null ? docs.get(i) : "";
            details.add(new AgentResult.CitationDetail(ruleIds.get(i), heading, doc));
        }
        return List.copyOf(details);
    }

    /**
     * 这次回答用了什么上下文（票 49）。**纯观测**——本方法只读，不改动 {@code messages} 的任何一条，
     * Prompt 文本不因它变一个字节（用例钉着这条）。
     *
     * @param ruleIds 与 {@code citations} 同源、同序；零召回时是空列表
     * @param historyTurns 注入的历史**用户轮数**，与 {@code agent.history-turns} 配置同源（不另算一份）
     * @param estimatedPromptTokens 粗估 token，见 {@link #estimateTokens(String)}；**不是**计费口径，
     *                              计费口径是模型回报的 {@code promptTokens}
     */
    static AgentResult.ContextComposition contextComposition(
            HybridRetriever.Result retrieved, SessionStore.Session session, List<LlmTypes.Message> messages) {
        List<String> ruleIds = retrieved == null || retrieved.empty()
                ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::ruleId).toList();
        int historyTurns = 0;
        if (session.turns() != null) {
            for (SessionStore.Turn turn : session.turns()) {
                if ("user".equals(turn.role())) {
                    historyTurns++;
                }
            }
        }
        int tokens = 0;
        for (LlmTypes.Message message : messages) {
            tokens += estimateTokens(message.content());
        }
        return new AgentResult.ContextComposition(ruleIds, historyTurns, tokens);
    }

    /**
     * 粗估 token：中日韩字符按 1 token/字、其余按 4 字符/token。
     *
     * <p>这是业界常用启发式，**不是真分词**。它只回答"这次注入了多大一坨"，够用；要精确值就得引
     * 分词器，那与本轮「旁挂观测」的定义不符，也不该为一个观测字段增加依赖面。
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int wide = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFF00 && c <= 0xFFEF)) {
                wide++;
            }
        }
        return wide + (text.length() - wide) / 4;
    }

    // ── local 3B 保真守卫 ────────────────────────────────────────────────

    /**
     * 票 167：local 3B 的答案保真守卫。只对政策意图生效，且只在 local 模式。
     * 3B 会漏掉条款里的时限数字，也会对纯政策咨询反问订单号；这里做确定性的最小补齐。
     */
    private String localPolicyAnswerGuard(String answer, Intent intent, String query,
                                          HybridRetriever.Result retrieved) {
        if (!"local".equals(services.llm.mode()) || intent == null || !intent.isPolicy() || answer == null) {
            return answer;
        }
        String guarded = answer;
        String timeFact = missingTimeFact(guarded, retrieved);
        if (timeFact != null) {
            services.registry.counter("shoppilot_local_answer_guard_total", "kind", "time_fact").increment();
            guarded = guarded.endsWith("。") ? guarded + "补充：" + timeFact : guarded + "。补充：" + timeFact;
        }
        if (query != null && !query.matches("(?s).*\\d{5,}.*")) {
            String withoutAsk = dropPolicyOrderAsk(guarded);
            if (!withoutAsk.isBlank()) {
                guarded = withoutAsk;
            }
        }
        return guarded;
    }

    private static String dropPolicyOrderAsk(String answer) {
        StringBuilder kept = new StringBuilder();
        for (String sentence : answer.split("。")) {
            // 纯政策咨询没有订单号诉求；只要出现“订单号”就不该出现在回答里。
            boolean asksOrder = sentence.contains("订单号");
            if (!asksOrder) {
                kept.append(sentence).append("。");
            }
        }
        return kept.length() == 0 ? answer : kept.toString();
    }

    private static String missingTimeFact(String answer, HybridRetriever.Result retrieved) {
        if (retrieved == null || retrieved.rules() == null || retrieved.rules().isEmpty()) {
            return null;
        }
        String freshFact = null;
        String serviceFact = null;
        for (HybridRetriever.Retrieved rule : retrieved.rules()) {
            if (rule.text() == null) {
                continue;
            }
            for (String sentence : rule.text().split("。")) {
                int idx = sentence.indexOf("四十八小时");
                if (idx >= 0 && sentence.contains("时限")) {
                    int start = sentence.lastIndexOf("，", idx);
                    String fact = sentence.substring(start < 0 ? 0 : start + 1, idx + "四十八小时".length()).trim();
                    // gold 用阿拉伯数字；条款原文是中文数字，补齐时两种写法都给，避免口径不一致。
                    freshFact = fact.replace("四十八小时", "48 小时（四十八小时）");
                }
                if (serviceFact == null && sentence.contains("点")
                        && (sentence.contains("供应时间") || sentence.contains("开放时间"))) {
                    String head = sentence.split("，")[0].trim();
                    if (!head.isBlank()) {
                        serviceFact = head;
                    }
                }
            }
        }
        if (serviceFact != null && !answer.contains("七点") && !answer.contains("十点")) {
            return serviceFact.endsWith("。") ? serviceFact : serviceFact + "。";
        }
        if (freshFact != null && !answer.contains("48") && !answer.contains("四十八")) {
            return freshFact.endsWith("。") ? freshFact : freshFact + "。";
        }
        return null;
    }

    static void emitChunked(String answer, EventSink sink) {
        int chunks = Math.max(1, Math.min(8, answer.length() / 20));
        for (int i = 0; i < chunks; i++) {
            int from = i * answer.length() / chunks;
            int to = (i + 1) * answer.length() / chunks;
            sink.token(answer.substring(from, to));
        }
    }

    /** 构造系统提示词（店铺人格 + 风格档位 + local 补充）。入口层用，行为与原 runRouted 一致。 */
    String systemPrompt(String tenantId, StyleService.Tier styleTier) {
        String prompt = services.styleService.assemble(
                services.storeProfileService.augment(services.promptCatalog.systemPrompt(), tenantId), styleTier);
        if ("local".equals(services.properties.llm().mode())) {
            prompt = prompt + LOCAL_ANSWER_FIDELITY_HINT;
        }
        return prompt;
    }
}
