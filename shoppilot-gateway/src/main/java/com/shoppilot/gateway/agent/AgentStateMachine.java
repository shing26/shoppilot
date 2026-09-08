package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheEntry;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.cache.QueryNormalizer;
import com.shoppilot.gateway.cache.SingleFlight;
import com.shoppilot.gateway.cache.WriteBackPolicy;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.llm.LlmException;
import com.shoppilot.gateway.llm.LlmGateway;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.triage.TriageEngine;
import com.shoppilot.gateway.triage.TriageResult;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolContracts;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.view.ToolStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * 有界 Agent 状态机（ADR 0008）。
 *
 * <p>工具循环硬上限 2 轮：脱离时延预算谈自由 agent 循环，在这个场景里是自杀式设计。
 */
@Component
public class AgentStateMachine {

    private static final Logger log = LoggerFactory.getLogger(AgentStateMachine.class);

    /** 单条条款注入上限：防止一次检索把 Prompt 撑爆，也保证 5 条条款可控。 */
    private static final int MAX_CLAUSE_CHARS = 600;

    private static final String SYSTEM_PROMPT = """
            你是电商店铺的在线客服助手。请遵守：
            1. 静态政策问题只依据【政策条款】回答；条款未覆盖时明确说明并建议转人工，不要编造。
            2. 禁止断言式个性化结论。涉及"我这种情况适不适用"时，说明需要查询具体订单才能确定，并主动提出帮用户查询。
            3. 需要查询或办理业务时调用工具；缺少必填参数一律向用户询问，绝不猜测订单号。
            4. 工具返回失败时，用自然中文向用户解释现状与下一步，不要复述错误码。
            5. 用简体中文，口语、简洁，不超过 200 字。
            """;

    private final TriageEngine triageEngine;
    private final CacheService cacheService;
    private final SingleFlight singleFlight;
    private final WriteBackPolicy writeBackPolicy;
    private final KbEpoch kbEpoch;
    private final HybridRetriever retriever;
    private final LlmGateway llm;
    private final ToolDispatcher dispatcher;
    private final SessionStore sessionStore;
    private final FallbackService fallbackService;
    private final GatewayProperties properties;
    private final ExecutorService writeBackExecutor;
    private final Counter toolRoundExhaustedCounter;

    public AgentStateMachine(TriageEngine triageEngine, CacheService cacheService, SingleFlight singleFlight,
                             WriteBackPolicy writeBackPolicy, KbEpoch kbEpoch, HybridRetriever retriever,
                             LlmGateway llm, ToolDispatcher dispatcher, SessionStore sessionStore,
                             FallbackService fallbackService, GatewayProperties properties,
                             ExecutorService writeBackExecutor, MeterRegistry registry) {
        this.triageEngine = triageEngine;
        this.cacheService = cacheService;
        this.singleFlight = singleFlight;
        this.writeBackPolicy = writeBackPolicy;
        this.kbEpoch = kbEpoch;
        this.retriever = retriever;
        this.llm = llm;
        this.dispatcher = dispatcher;
        this.sessionStore = sessionStore;
        this.fallbackService = fallbackService;
        this.properties = properties;
        this.writeBackExecutor = writeBackExecutor;
        this.toolRoundExhaustedCounter = Counter.builder("shoppilot_tool_round_exhausted_total").register(registry);
    }

    public AgentResult run(String query, String idempotencyToken, EventSink sink) {
        List<AgentResult.TraceStep> trace = new ArrayList<>();
        String tenantId = TenantContext.tenantId();
        String customerId = TenantContext.customerId();
        String conversationId = TenantContext.current().conversationId();
        step(trace, sink, AgentState.INTAKE, "tenant=" + tenantId);

        SessionStore.Session session = sessionStore.load(tenantId, conversationId);
        long epoch = kbEpoch.current();

        if (session.hasPending()) {
            sink.meta(conversationId, null, CacheService.Layer.NONE);
            return resumePending(session, query, idempotencyToken, tenantId, conversationId, trace, sink);
        }

        step(trace, sink, AgentState.TRIAGE, "开始判定");
        TriageEngine.Outcome outcome = triageEngine.triage(query);
        TriageResult triage = outcome.result();
        step(trace, sink, AgentState.TRIAGE,
                "layer=" + triage.layer() + " intent=" + triage.intent() + " admissible=" + triage.cacheAdmissible());

        if (triage.intent() == Intent.ESCALATE) {
            sink.meta(conversationId, Intent.ESCALATE, CacheService.Layer.NONE);
            return fallback(AgentState.TRIAGE, trace, sink, FallbackReason.USER_REQUESTED, query, null);
        }

        if (triage.cacheAdmissible()) {
            step(trace, sink, AgentState.CACHE_READ, "intent=" + triage.intent());
            CacheService.Lookup lookup = cacheService.lookup(tenantId, triage.intent(), query, epoch,
                    outcome.queryVector());
            if (lookup.hit()) {
                sink.meta(conversationId, triage.intent(), lookup.layer());
                step(trace, sink, AgentState.REPLY, "cache=" + lookup.layer());
                CacheEntry entry = lookup.entry().get();
                sink.token(entry.answer());
                return new AgentResult(entry.answer(), triage.intent(), triage.layer(), lookup.layer(),
                        entry.sourceRuleIds(), trace, null, null, false, 0, 0, false, false);
            }
            if (lookup.negative()) {
                sink.meta(conversationId, triage.intent(), CacheService.Layer.NONE);
                step(trace, sink, AgentState.CACHE_READ, "negative-marker");
                return fallback(AgentState.CACHE_READ, trace, sink, FallbackReason.INTENT_UNRESOLVED, query,
                        "该问题此前已确认无对应政策条款");
            }
            // 穿透合并：同一 key 只放一个请求进模型（ADR 0006）
            String flightKey = "shoppilot:c:flight:" + QueryNormalizer.md5(
                    tenantId + "|" + triage.intent().name() + "|" + lookup.normalizedQuery());
            SingleFlight.Gate gate = singleFlight.join(flightKey);
            if (!gate.leader()) {
                sink.meta(conversationId, triage.intent(), CacheService.Layer.FLIGHT);
                step(trace, sink, AgentState.CACHE_READ, "singleflight-reuse");
                gate.shared().ifPresent(entry -> sink.token(entry.answer()));
                return gate.shared()
                        .map(entry -> new AgentResult(entry.answer(), triage.intent(), triage.layer(),
                                CacheService.Layer.FLIGHT, entry.sourceRuleIds(), trace, null, null, false,
                                0, 0, false, false))
                        .orElseGet(() -> fallback(AgentState.CACHE_READ, trace, sink,
                                FallbackReason.INTENT_UNRESOLVED, query, null));
            }
            boolean published = false;
            try {
                sink.meta(conversationId, triage.intent(), CacheService.Layer.NONE);
                ModelRun run = runModelPath(triage, lookup, query, idempotencyToken, tenantId, customerId,
                        conversationId, session, epoch, trace, sink);
                // 只有真的写回缓存的那条答案才广播给等待者，降级话术一律不共享（ADR 0006）
                published = run.shareable();
                singleFlight.publish(flightKey, run.cacheEntry());
                return run.result();
            } finally {
                if (!published) {
                    singleFlight.abandon(flightKey);
                }
            }
        }

        sink.meta(conversationId, triage.intent(), CacheService.Layer.NONE);
        return runModelPath(triage, CacheService.Lookup.disabled(), query, idempotencyToken, tenantId, customerId,
                conversationId, session, epoch, trace, sink).result();
    }

    /**
     * @param cacheEntry 本次真正写回缓存的那条答案，穿透等待者复用的就是它；未写回时为 empty
     */
    private record ModelRun(AgentResult result, Optional<CacheEntry> cacheEntry) {

        static ModelRun solo(AgentResult result) {
            return new ModelRun(result, Optional.empty());
        }

        boolean shareable() {
            return cacheEntry.isPresent();
        }

    }

    private ModelRun runModelPath(TriageResult triage, CacheService.Lookup lookup,
                                  String query, String idempotencyToken, String tenantId, String customerId,
                                  String conversationId, SessionStore.Session session, long epoch,
                                  List<AgentResult.TraceStep> trace, EventSink sink) {
        Intent intent = triage.intent();
        List<LlmTypes.Message> messages = new ArrayList<>();
        messages.add(LlmTypes.Message.system(SYSTEM_PROMPT));
        appendHistory(messages, session);

        HybridRetriever.Result retrieved = null;
        if (intent == null || intent == Intent.UNKNOWN || intent.isPolicy()) {
            step(trace, sink, AgentState.RETRIEVE, "intent=" + intent);
            try {
                retrieved = retriever.retrieve(query, tenantId, intent == Intent.UNKNOWN ? null : intent);
            } catch (RuntimeException retrievalFailure) {
                log.warn("混合检索失败，本轮无政策上下文: {}", retrievalFailure.getMessage());
                retrieved = new HybridRetriever.Result(List.of(), 0, 0, epoch);
            }
            step(trace, sink, AgentState.RETRIEVE, "dense=" + retrieved.denseHits() + " lexical=" + retrieved.lexicalHits()
                    + " fused=" + retrieved.rules().size());
        }
        messages.add(LlmTypes.Message.user(composeUserMessage(query, retrieved)));

        boolean toolUsed = false;
        List<LlmTypes.Reply> roundReplies = new ArrayList<>();
        int rounds = 0;
        LlmTypes.Reply lastReply = null;
        while (rounds < properties.agent().maxToolRounds()) {
            step(trace, sink, AgentState.PLAN, "round=" + rounds);
            LlmTypes.Request planRequest = new LlmTypes.Request(messages, toolsFor(triage),
                    properties.llm().temperature());
            try {
                lastReply = llm.complete(planRequest);
            } catch (LlmException failure) {
                return ModelRun.solo(fallback(AgentState.PLAN, trace, sink, mapLlmFailure(failure), query,
                        failure.getMessage()));
            }
            roundReplies.add(lastReply);
            if (!lastReply.wantsTool()) {
                break;
            }
            toolUsed = true;
            rounds++;
            LlmTypes.ToolCall call = lastReply.toolCalls().get(0);
            messages.add(LlmTypes.Message.assistant(lastReply.content(), lastReply.toolCalls()));
            ToolDispatcher.Dispatch dispatch = dispatcher.dispatch(call, idempotencyToken);
            if (dispatch.unknown()) {
                // 模型编出了不存在的工具：不拿 null 工具往下走，直接兜底
                return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE,
                        query, "未注册的工具 " + call.name()));
            }
            if (dispatch.fabricated()) {
                // 模型凭空编了一个订单号：绝不拿它去撞库，退回来向用户追问合法订单号
                step(trace, sink, AgentState.TOOL_EXEC, "fabricated-orderNo 已拦截");
                return ModelRun.solo(askSlot(session, tenantId, conversationId, dispatch, query, trace, sink));
            }
            if (dispatch.needsSlot()) {
                return ModelRun.solo(askSlot(session, tenantId, conversationId, dispatch, query, trace, sink));
            }
            step(trace, sink, AgentState.TOOL_EXEC, dispatch.tool() + "=" + dispatch.status());
            sink.toolExecuting(dispatch.tool(), dispatch.label());
            sink.toolResult(dispatch.tool(), dispatch.status(), summarize(dispatch));
            if (dispatch.degraded()) {
                return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE,
                        query, dispatch.json()));
            }
            messages.add(LlmTypes.Message.tool(call.id(), dispatch.json()));
        }
        if (rounds >= properties.agent().maxToolRounds() && lastReply != null && lastReply.wantsTool()) {
            // 还有工具想调但轮次用尽：不再进循环，直接基于已有事实收尾
            toolRoundExhaustedCounter.increment();
            step(trace, sink, AgentState.PLAN, "tool-rounds-exhausted");
        }

        step(trace, sink, AgentState.REPLY, "streaming");
        String answer;
        int promptTokens = 0;
        int completionTokens = 0;
        if (lastReply != null && !lastReply.wantsTool() && lastReply.content() != null && rounds == 0) {
            // 首轮就给出内容：直接把它按打字机切块推出去，不再多打一次模型
            answer = lastReply.content();
            promptTokens = lastReply.promptTokens();
            completionTokens = lastReply.completionTokens();
            emitChunked(answer, sink);
        } else {
            try {
                LlmTypes.Reply finalReply = llm.stream(new LlmTypes.Request(messages, List.of(),
                        properties.llm().temperature()), sink::token);
                answer = finalReply.content();
                promptTokens = sum(roundReplies, true) + finalReply.promptTokens();
                completionTokens = sum(roundReplies, false) + finalReply.completionTokens();
            } catch (LlmException failure) {
                return ModelRun.solo(fallback(AgentState.REPLY, trace, sink, mapLlmFailure(failure), query,
                        failure.getMessage()));
            }
        }

        sessionStore.save(tenantId, sessionStore.appendTurn(
                clearPending(session), query, answer));

        List<String> citations = retrieved == null ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::ruleId).toList();
        List<String> citedScopes = retrieved == null ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::scope).toList();

        WriteBackPolicy.Verdict verdict = writeBackPolicy.evaluate(new WriteBackPolicy.Request(
                intent, answer, retrieved != null && !retrieved.empty(), toolUsed, false, false));
        Optional<CacheEntry> written = Optional.empty();
        if (verdict.eligible()) {
            Optional<CacheEntry> prepared = cacheService.prepareWrite(tenantId, intent, epoch, lookup, answer,
                    citedScopes, citations, properties.llm().model());
            if (prepared.isEmpty()) {
                // 准入判定没给出向量（例如缓存被关），写回无从落点
                step(trace, sink, AgentState.CACHE_WRITE, "skipped:no-write-target");
            } else {
                step(trace, sink, AgentState.CACHE_WRITE, "eligible");
                written = prepared;
                CacheService.Lookup forWrite = lookup;
                CacheEntry entry = prepared.get();
                writeBackExecutor.execute(() -> {
                    try {
                        cacheService.writeBack(entry, forWrite);
                    } catch (Exception failure) {
                        log.warn("异步写回失败，不影响本次响应: {}", failure.getMessage());
                    }
                });
            }
        } else {
            step(trace, sink, AgentState.CACHE_WRITE, "rejected:" + verdict.reason());
            if (retrieved != null && retrieved.empty() && intent != null && intent.cacheAdmissible()) {
                cacheService.writeNegative(tenantId, intent, epoch, lookup);
            }
        }

        return new ModelRun(new AgentResult(answer, intent, triage.layer(), CacheService.Layer.NONE, citations, trace,
                null, null, false, promptTokens, completionTokens, toolUsed, false), written);
    }

    /** 缺槽位：追问一次，仍缺则转人工。绝不猜（ADR 0008、ticket 11）。 */
    private AgentResult askSlot(SessionStore.Session session, String tenantId, String conversationId,
                                ToolDispatcher.Dispatch dispatch, String query,
                                List<AgentResult.TraceStep> trace, EventSink sink) {
        int asks = session.slotAskCount() + 1;
        if (asks > properties.agent().maxSlotAsks()) {
            return fallback(AgentState.SLOT_ASK, trace, sink, FallbackReason.SLOT_UNRESOLVED, query,
                    "已追问 " + session.slotAskCount() + " 次仍缺槽位");
        }
        String slot = dispatch.missingSlots().get(0);
        String question = dispatcher.question(dispatch.tool(), dispatch.missingSlots());
        step(trace, sink, AgentState.SLOT_ASK, dispatch.tool() + " 缺 " + slot);
        sink.slotAsk(slot, question);
        sessionStore.save(tenantId, new SessionStore.Session(conversationId, session.turns(),
                dispatch.tool().apiName(), new LinkedHashMap<>(Map.of("askedSlot", slot)), asks));
        return new AgentResult(question, dispatch.tool().intent(), "SLOT", CacheService.Layer.NONE, List.of(), trace,
                null, null, true, 0, 0, false, false);
    }

    /**
     * 上一轮追问过槽位，这一轮把用户补充的信息与原始诉求合并后重放工具。
     * 这里刻意不再进模型：订单号这类槽位用正则就能取，交给模型只会更慢更贵。
     */
    private AgentResult resumePending(SessionStore.Session session, String query, String idempotencyToken,
                                      String tenantId, String conversationId, List<AgentResult.TraceStep> trace,
                                      EventSink sink) {
        step(trace, sink, AgentState.SLOT_ASK, "合并补充信息");
        ToolName tool = ToolName.fromApiName(session.pendingTool());
        if (tool == null) {
            sessionStore.save(tenantId, clearPending(session));
            return fallback(AgentState.SLOT_ASK, trace, sink, FallbackReason.INTENT_UNRESOLVED, query, "待办工具已失效");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?<!\\d)(\\d{5,8})(?!\\d)").matcher(query);
        if (matcher.find()) {
            arguments.put("orderNo", matcher.group(1));
        }
        if (tool == ToolName.MODIFY_DELIVERY_ADDRESS) {
            java.util.regex.Matcher phone = java.util.regex.Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)").matcher(query);
            if (phone.find()) {
                arguments.put("receiverPhone", phone.group(1));
            }
        }
        if (tool == ToolName.APPLY_REFUND && !query.isBlank()) {
            arguments.put("reason", query.length() > 60 ? query.substring(0, 60) : query);
        }
        List<String> missing = dispatcher.missingSlots(tool, arguments);
        if (!missing.isEmpty()) {
            String slot = missing.get(0);
            String question = dispatcher.question(tool, missing);
            int asks = session.slotAskCount() + 1;
            if (asks > properties.agent().maxSlotAsks()) {
                return fallback(AgentState.SLOT_ASK, trace, sink, FallbackReason.SLOT_UNRESOLVED, query, null);
            }
            sink.slotAsk(slot, question);
            sessionStore.save(tenantId, new SessionStore.Session(conversationId, session.turns(), tool.apiName(),
                    arguments, asks));
            return new AgentResult(question, tool.intent(), "SESSION", CacheService.Layer.NONE, List.of(), trace,
                    null, null, true, 0, 0, false, false);
        }
        LlmTypes.ToolCall call = new LlmTypes.ToolCall("resumed", tool.apiName(), arguments);
        ToolDispatcher.Dispatch dispatch = dispatcher.dispatch(call, idempotencyToken);
        step(trace, sink, AgentState.TOOL_EXEC, tool + "=" + dispatch.status());
        sink.toolExecuting(tool, dispatch.label());
        sink.toolResult(tool, dispatch.status(), summarize(dispatch));
        sessionStore.save(tenantId, clearPending(session));
        if (dispatch.degraded()) {
            return fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE, query, dispatch.json());
        }
        List<LlmTypes.Message> messages = new ArrayList<>();
        messages.add(LlmTypes.Message.system(SYSTEM_PROMPT));
        messages.add(LlmTypes.Message.user(query + "\n\n【业务系统返回】\n" + dispatch.json()));
        try {
            LlmTypes.Reply reply = llm.stream(new LlmTypes.Request(messages, List.of(),
                    properties.llm().temperature()), sink::token);
            sessionStore.save(tenantId, sessionStore.appendTurn(clearPending(session), query, reply.content()));
            return new AgentResult(reply.content(), tool.intent(), "SESSION", CacheService.Layer.NONE, List.of(),
                    trace, null, null, false, reply.promptTokens(), reply.completionTokens(), true, false);
        } catch (LlmException failure) {
            return fallback(AgentState.REPLY, trace, sink, mapLlmFailure(failure), query, failure.getMessage());
        }
    }

    private AgentResult fallback(AgentState from, List<AgentResult.TraceStep> trace, EventSink sink,
                                 FallbackReason reason, String query, String detail) {
        step(trace, sink, AgentState.FALLBACK, reason.name() + (detail == null ? "" : " " + detail));
        Optional<String> ticket = fallbackService.escalate(reason, query, detail);
        sink.fallback(reason, ticket.orElse(null));
        String answer = reason.userMessage() + ticket.map(id -> "（工单号 " + id + "）").orElse("");
        sink.token(answer);
        return new AgentResult(answer, Intent.ESCALATE, "FALLBACK", CacheService.Layer.NONE, List.of(), trace,
                reason, ticket.orElse(null), false, 0, 0, false, true);
    }

    /**
     * 按意图裁剪工具集（ADR 0007）。
     *
     * <p>政策咨询一律不下发工具：实测给了工具，小模型会凭空编一个订单号去查，
     * 把一句政策咨询答成"查不到您的订单"。判定未定案时才全量下发，让工具选择本身充当意图证据。
     */
    private List<Map<String, Object>> toolsFor(TriageResult triage) {
        Intent intent = triage.intent();
        if (intent == null || intent == Intent.UNKNOWN) {
            return ToolContracts.functionDescriptors();
        }
        return intent.isAction() ? ToolContracts.functionDescriptorsFor(intent) : List.of();
    }

    private static FallbackReason mapLlmFailure(LlmException failure) {
        return switch (failure.kind()) {
            case TIMEOUT -> FallbackReason.LLM_TIMEOUT;
            case UNAVAILABLE -> FallbackReason.LLM_CIRCUIT_OPEN;
            case BUDGET_EXCEEDED -> FallbackReason.LLM_BUDGET_EXCEEDED;
        };
    }

    private String composeUserMessage(String query, HybridRetriever.Result retrieved) {
        if (retrieved == null || retrieved.empty()) {
            return "【政策条款】\n（本轮未检索到相关条款）\n\n【买家问题】\n" + query;
        }
        StringBuilder builder = new StringBuilder("【政策条款】\n");
        int index = 1;
        for (HybridRetriever.Retrieved rule : retrieved.rules()) {
            builder.append(index++).append(". [").append(rule.ruleId()).append("] ")
                    .append(ruleText(rule)).append('\n');
        }
        builder.append("\n【买家问题】\n").append(query);
        return builder.toString();
    }

    private String ruleText(HybridRetriever.Retrieved rule) {
        String text = rule.text() == null ? "" : rule.text().trim();
        if (text.isEmpty()) {
            return "（该条款正文未能取回，仅可引用编号 " + rule.ruleId() + "）";
        }
        return text.length() > MAX_CLAUSE_CHARS ? text.substring(0, MAX_CLAUSE_CHARS) + "..." : text;
    }

    private static void appendHistory(List<LlmTypes.Message> messages, SessionStore.Session session) {
        if (session.turns() == null) {
            return;
        }
        for (SessionStore.Turn turn : session.turns()) {
            messages.add(new LlmTypes.Message(turn.role(), turn.text(), null, List.of()));
        }
    }

    private static SessionStore.Session clearPending(SessionStore.Session session) {
        return new SessionStore.Session(session.conversationId(), session.turns(), null, new LinkedHashMap<>(), 0);
    }

    private static int sum(List<LlmTypes.Reply> replies, boolean prompt) {
        int total = 0;
        for (LlmTypes.Reply reply : replies) {
            total += prompt ? reply.promptTokens() : reply.completionTokens();
        }
        return total;
    }

    private static String summarize(ToolDispatcher.Dispatch dispatch) {
        return dispatch.status() == null ? "无结果" : dispatch.status().name();
    }

    private static void emitChunked(String answer, EventSink sink) {
        int chunks = Math.max(1, Math.min(8, answer.length() / 20));
        for (int i = 0; i < chunks; i++) {
            int from = i * answer.length() / chunks;
            int to = (i + 1) * answer.length() / chunks;
            sink.token(answer.substring(from, to));
        }
    }

    private static void step(List<AgentResult.TraceStep> trace, EventSink sink, AgentState state, String detail) {
        trace.add(new AgentResult.TraceStep(state.name(), System.currentTimeMillis(), detail));
        sink.status(state, detail);
    }
}
