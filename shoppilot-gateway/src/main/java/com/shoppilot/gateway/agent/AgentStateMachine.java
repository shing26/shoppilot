package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheEntry;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.cache.QueryNormalizer;
import com.shoppilot.gateway.cache.SingleFlight;
import com.shoppilot.gateway.cache.WriteBackPolicy;
import com.shoppilot.gateway.cache.WriteBackPool;
import com.shoppilot.gateway.channel.ChannelContext;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.llm.LlmException;
import com.shoppilot.gateway.llm.LlmGateway;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.sentiment.SentimentGate;
import com.shoppilot.gateway.style.StyleService;
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

/**
 * 有界 Agent 状态机（ADR 0008）。
 *
 * <p>工具循环硬上限 2 轮：脱离时延预算谈自由 agent 循环，在这个场景里是自杀式设计。
 */
@Component
public class AgentStateMachine {

    private static final com.fasterxml.jackson.databind.ObjectMapper TRACE_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(AgentStateMachine.class);

    /** 单条条款注入上限：防止一次检索把 Prompt 撑爆，也保证 5 条条款可控。 */
    private static final int MAX_CLAUSE_CHARS = 600;

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
    private final WriteBackPool writeBackPool;
    private final PromptCatalog promptCatalog;
    private final SentimentGate sentimentGate;
    private final StyleService styleService;
    private final MeterRegistry registry;
    private final Counter toolRoundExhaustedCounter;
    private final Counter negativeSuppressedCounter;
    private final Counter writeNudgeCounter;
    private final Counter multiToolCallCounter;

    public AgentStateMachine(TriageEngine triageEngine, CacheService cacheService, SingleFlight singleFlight,
                             WriteBackPolicy writeBackPolicy, KbEpoch kbEpoch, HybridRetriever retriever,
                             LlmGateway llm, ToolDispatcher dispatcher, SessionStore sessionStore,
                             FallbackService fallbackService, GatewayProperties properties,
                             WriteBackPool writeBackPool, PromptCatalog promptCatalog, SentimentGate sentimentGate,
                             StyleService styleService, MeterRegistry registry) {
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
        this.writeBackPool = writeBackPool;
        this.promptCatalog = promptCatalog;
        this.sentimentGate = sentimentGate;
        this.styleService = styleService;
        this.registry = registry;
        this.toolRoundExhaustedCounter = Counter.builder("shoppilot_tool_round_exhausted_total").register(registry);
        // 被拦下来的"不该写的负缓存"要看得见：它是这条防线在中间件抖动时确实生效的唯一证据
        this.negativeSuppressedCounter = Counter.builder("shoppilot_cache_negative_suppressed_total")
                .tag("reason", "retrieval-degraded").register(registry);
        // "答应了但没动手"被纠偏了几次：这是工具闭环里被救回来的那一段，不数出来就没人知道它存在
        this.writeNudgeCounter = Counter.builder("shoppilot_write_nudge_total").register(registry);
        // 模型一次要了多个工具的次数：请求层已带 parallel_tool_calls=false（票 41），
        // 计数非零说明对面端点忽略了该字段，残余风险就靠它暴露
        this.multiToolCallCounter = Counter.builder("shoppilot_llm_multi_tool_calls_total").register(registry);
    }

    public AgentResult run(String query, String idempotencyToken, EventSink sink) {
        List<AgentResult.TraceStep> trace = new ArrayList<>();
        String tenantId = TenantContext.tenantId();
        String customerId = TenantContext.customerId();
        String conversationId = TenantContext.current().conversationId();
        step(trace, sink, AgentState.INTAKE, "tenant=" + tenantId);

        // 会话归属是「店铺 + 买家」两者（ADR 0025）：只按店铺载会话，同店铺里换个人填同一个会话 id，
        // 载出来的就是别人的对话轮次与别人没办完的待办动作。
        SessionStore.Session session = sessionStore.load(tenantId, customerId, conversationId);
        long epoch = kbEpoch.current();

        // 情绪门（ADR 0034）：INTAKE → TRIAGE 之间，词典层 0 token。高情绪在这里直接落工单转人工，
        // 不让激动的买家走完检索/模型/工具全链路才被转接——等待本身就是二次激怒。
        // 状态机 10 状态不扩：情绪判定发生在 INTAKE 状态内部；perf 口径下第二层 LLM 分类不启用。
        SentimentGate.Verdict sentiment = sentimentGate.evaluate(query);
        step(trace, sink, AgentState.INTAKE, "sentiment=" + sentiment.emotion() + " via " + sentiment.source());
        // 风格档位（ADR 0038）：channel × emotion × intent → 档位，注入段拼在版本化基座之后；
        // 回答正文仍由同一次 LLM 调用产出。档位随参数穿透到各降级出口，话术选择随档位联动。
        StyleService.Tier styleTier = styleService.tierFor(ChannelContext.current(), sentiment.emotion(), null);
        registry.counter("shoppilot_style_applied_total", "style", styleTier.name()).increment();
        // 档位同时进 trace（与 sentiment= 同构）：离线评测与验收脚本按同步响应就能归因提示词形态，
        // 不必为它新增响应字段（票 35 登记的"离线归因"触发条件由此关闭）
        step(trace, sink, AgentState.INTAKE, "style=" + styleTier.name());
        sink.style(styleTier.name());
        String systemPrompt = styleService.assemble(promptCatalog.systemPrompt(), styleTier);
        if (sentiment.escalated()) {
            sink.meta(conversationId, null, CacheService.Layer.NONE);
            return fallback(AgentState.INTAKE, trace, sink, FallbackReason.EMOTION_ESCALATION, query,
                    "emotion=" + sentiment.emotion() + " via " + sentiment.source(), styleTier);
        }

        if (session.hasPending()) {
            sink.meta(conversationId, null, CacheService.Layer.NONE);
            return resumePending(session, query, idempotencyToken, tenantId, customerId, conversationId, trace, sink,
                    systemPrompt, styleTier);
        }

        step(trace, sink, AgentState.TRIAGE, "开始判定");
        TriageEngine.Outcome outcome = triageEngine.triage(query);
        TriageResult triage = outcome.result();
        step(trace, sink, AgentState.TRIAGE,
                "layer=" + triage.layer() + " intent=" + triage.intent() + " admissible=" + triage.cacheAdmissible());

        if (triage.intent() == Intent.ESCALATE) {
            sink.meta(conversationId, Intent.ESCALATE, CacheService.Layer.NONE);
            return fallback(AgentState.TRIAGE, trace, sink, FallbackReason.USER_REQUESTED, query, null, styleTier);
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
                        entry.sourceRuleIds(), trace, null, null, false, 0, 0, false, false,
                        promptCatalog.version());
            }
            if (lookup.negative()) {
                sink.meta(conversationId, triage.intent(), CacheService.Layer.NONE);
                step(trace, sink, AgentState.CACHE_READ, "negative-marker");
                return fallback(AgentState.CACHE_READ, trace, sink, FallbackReason.INTENT_UNRESOLVED, query,
                        "该问题此前已确认无对应政策条款", styleTier);
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
                                0, 0, false, false, promptCatalog.version()))
                        .orElseGet(() -> fallback(AgentState.CACHE_READ, trace, sink,
                                FallbackReason.INTENT_UNRESOLVED, query, null, styleTier));
            }
            boolean published = false;
            try {
                sink.meta(conversationId, triage.intent(), CacheService.Layer.NONE);
                ModelRun run = runModelPath(triage, lookup, query, idempotencyToken, tenantId, customerId,
                        conversationId, session, epoch, trace, sink, systemPrompt, styleTier);
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
                conversationId, session, epoch, trace, sink, systemPrompt, styleTier).result();
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
                                  List<AgentResult.TraceStep> trace, EventSink sink, String systemPrompt,
                                  StyleService.Tier styleTier) {
        Intent intent = triage.intent();
        List<LlmTypes.Message> messages = new ArrayList<>();
        messages.add(LlmTypes.Message.system(systemPrompt));
        appendHistory(messages, session);

        HybridRetriever.Result retrieved = null;
        if (intent == null || intent == Intent.UNKNOWN || intent.isPolicy()) {
            step(trace, sink, AgentState.RETRIEVE, "intent=" + intent);
            try {
                retrieved = retriever.retrieve(query, tenantId, intent == Intent.UNKNOWN ? null : intent);
            } catch (RuntimeException retrievalFailure) {
                log.warn("混合检索失败，本轮无政策上下文: {}", retrievalFailure.getMessage());
                retrieved = HybridRetriever.Result.unavailable(epoch);
            }
            step(trace, sink, AgentState.RETRIEVE, "dense=" + retrieved.denseHits() + " lexical=" + retrieved.lexicalHits()
                    + " fused=" + retrieved.rules().size() + " degraded=" + retrieved.degraded());
        }
        messages.add(LlmTypes.Message.user(composeUserMessage(query, retrieved)));

        boolean toolUsed = false;
        List<LlmTypes.Reply> roundReplies = new ArrayList<>();
        int rounds = 0;
        // 用户明确要求办理的写动作：状态机记得它必须真的执行过，一句文字承诺不算办完
        ToolName expectedWrite = expectedWriteTool(intent);
        boolean expectedWriteDone = false;
        boolean nudged = false;
        boolean answeredByBudgetCheck = false;
        // 计划（ADR 0036）：按执行顺序留存每步结果 JSON，供后步参数表达式取值；中止标记让指标分账
        List<String> stepResults = new ArrayList<>();
        boolean planAborted = false;
        LlmTypes.Reply lastReply = null;
        while (rounds < properties.agent().maxToolRounds()) {
            step(trace, sink, AgentState.PLAN, "round=" + rounds);
            LlmTypes.Request planRequest = new LlmTypes.Request(messages, toolsFor(triage),
                    properties.llm().temperature());
            try {
                lastReply = llm.complete(planRequest);
            } catch (LlmException failure) {
                return ModelRun.solo(fallback(AgentState.PLAN, trace, sink, mapLlmFailure(failure), query,
                        failure.getMessage(), styleTier));
            }
            roundReplies.add(lastReply);
            if (!lastReply.wantsTool()) {
                if (expectedWrite == null || expectedWriteDone || nudged) {
                    break;
                }
                // 模型用文字承诺了业务动作却没调用工具（dev 评测实测形态："我这就为您提交退款申请"之后直接收尾）。
                // 只纠偏一次，且只在答案本来就不成立的请求上多花一次规划调用；工具轮次上限不变（ADR 0008）。
                nudged = true;
                writeNudgeCounter.increment();
                step(trace, sink, AgentState.PLAN, "corrective=" + expectedWrite.apiName());
                messages.add(LlmTypes.Message.assistant(lastReply.content(), List.of()));
                messages.add(LlmTypes.Message.user("你刚才承诺了要办「" + expectedWrite.description() + "」，但没有调用对应工具。"
                        + "现在直接调用 " + expectedWrite.apiName() + " 把它真的办掉，参数从上面的对话里取；"
                        + "必填参数确实缺失就照实向用户追问，不要再复述计划。如果你判断这个动作本就不该做，请说明理由。"));
                continue;
            }
            toolUsed = true;
            rounds++;
            // 防御（票 41）：请求层已带 parallel_tool_calls=false，但宽松端点仍可能一次返回多个调用。
            // 只派发并只记录第一个：转录里 assistant 的 tool_call 必须与 tool 响应一一配对，
            // 不伪造未执行工具的结果；多出来的调用不执行也不假装执行，计数器让这件事可见。
            if (lastReply.toolCalls().size() > 1) {
                multiToolCallCounter.increment();
            }
            LlmTypes.ToolCall rawCall = lastReply.toolCalls().get(0);
            // 计划步骤的参数表达式（ADR 0036）：只允许 {steps[i].result.<field>} 这一种形态。
            // 不合语法 / 引用未执行的前步 / 字段不存在 → 整条计划拒收，fail-closed 不执行。
            PlanExpression.Resolution resolution =
                    PlanExpression.resolve(rawCall.arguments(), stepResults, TRACE_JSON);
            if (resolution.rejected()) {
                registry.counter("shoppilot_plan_steps_total", "steps", "rejected").increment();
                step(trace, sink, AgentState.PLAN, "plan-rejected expression=" + resolution.rejectedExpression());
                return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE,
                        query, "参数表达式不合法，已拒绝执行：" + resolution.rejectedExpression(), styleTier));
            }
            LlmTypes.ToolCall call = new LlmTypes.ToolCall(rawCall.id(), rawCall.name(), resolution.arguments());
            messages.add(LlmTypes.Message.assistant(lastReply.content(), List.of(call)));
            ToolDispatcher.Dispatch dispatch = dispatcher.dispatch(call, idempotencyToken);
            if (dispatch.unknown()) {
                // 模型编出了不存在的工具：不拿 null 工具往下走，直接兜底
                return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE,
                        query, "未注册的工具 " + call.name(), styleTier));
            }
            if (dispatch.fabricated()) {
                // 模型凭空编了一个订单号：绝不拿它去撞库，退回来向用户追问合法订单号
                step(trace, sink, AgentState.TOOL_EXEC,
                        "fabricated-orderNo 已拦截 modelArgs=" + traceArgs(call.arguments()));
                return ModelRun.solo(askSlot(session, tenantId, customerId, conversationId, dispatch, query, trace, sink, styleTier));
            }
            if (dispatch.needsSlot()) {
                step(trace, sink, AgentState.TOOL_EXEC,
                        dispatch.tool() + " missing=" + dispatch.missingSlots()
                                + " modelArgs=" + traceArgs(call.arguments()));
                return ModelRun.solo(askSlot(session, tenantId, customerId, conversationId, dispatch, query, trace, sink, styleTier));
            }
            if (dispatch.tool() == expectedWrite) {
                expectedWriteDone = true;
            }
            step(trace, sink, AgentState.TOOL_EXEC,
                    dispatch.tool() + "=" + dispatch.status() + " modelArgs=" + traceArgs(call.arguments()));
            if (dispatch.duplicate()) {
                // 幂等命中：业务动作没有再执行，推"正在查询"是在骗用户；直接回放首次结果
                sink.duplicateSubmit(dispatch.tool(), "该请求已处理过，本次未重复执行");
            } else {
                sink.toolExecuting(dispatch.tool(), dispatch.label());
            }
            sink.toolResult(dispatch.tool(), dispatch.status(), summarize(dispatch));
            if (dispatch.degraded()) {
                return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE,
                        query, dispatch.json(), styleTier));
            }
            stepResults.add(dispatch.json());
            messages.add(LlmTypes.Message.tool(call.id(), dispatch.json()));
            if (failedStep(dispatch.status()) && rounds < properties.agent().maxToolRounds()) {
                // 前步失败即中止整条 Plan（ADR 0036）：后步的前提已不成立，不让模型继续往下调。
                // 只有"还有剩余步数"时才算中止——最后一步失败本就无后步可中止，照常进收尾轮，
                // 否则 aborted 桶会被"冗余跟进失败"灌满，失去"前提断裂"这层语义。
                planAborted = true;
                registry.counter("shoppilot_plan_steps_total", "steps", "aborted").increment();
                step(trace, sink, AgentState.TOOL_EXEC, "plan-aborted status=" + dispatch.status());
                break;
            }
        }
        if (!planAborted && toolUsed) {
            registry.counter("shoppilot_plan_steps_total", "steps", String.valueOf(rounds)).increment();
        }
        if (rounds >= properties.agent().maxToolRounds() && lastReply != null && lastReply.wantsTool()) {
            // 工具轮预算用尽（ADR 0008：硬上限 2 轮）。这里分三种走向，对齐 ADR 0008 全文：
            // ① 写动作答应过但还没真办成：轮次没了就不给"口头承诺收尾"留门，直接转人工（票 41 守卫）；
            // ② 预算外再做一次规划调用问模型还要不要工具（这是模型调用，不是第三个工具轮，
            //    与纠偏轮同例——ticket 11：rounds 只统计真正执行过的工具）：
            //    仍要 → 按字面"超限强制 FALLBACK"落工单；不要 → 它的正文就是最终答案，
            //    "先查订单再查物流"这类两轮链照常出答案（ADR 0008 Consequences）。
            if (expectedWrite != null && !expectedWriteDone) {
                toolRoundExhaustedCounter.increment();
                step(trace, sink, AgentState.PLAN, "tool-rounds-exhausted write-pending=" + expectedWrite.apiName());
                return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink,
                        FallbackReason.TOOL_ROUNDS_EXHAUSTED, query,
                        "轮次上限内未能完成 " + expectedWrite.apiName(), styleTier));
            }
            step(trace, sink, AgentState.PLAN, "budget-check");
            LlmTypes.Request budgetRequest = new LlmTypes.Request(messages, toolsFor(triage),
                    properties.llm().temperature());
            try {
                lastReply = llm.complete(budgetRequest);
            } catch (LlmException failure) {
                return ModelRun.solo(fallback(AgentState.PLAN, trace, sink, mapLlmFailure(failure), query,
                        failure.getMessage(), styleTier));
            }
            roundReplies.add(lastReply);
            if (lastReply.wantsTool()) {
                toolRoundExhaustedCounter.increment();
                step(trace, sink, AgentState.PLAN, "tool-rounds-exhausted");
                return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink,
                        FallbackReason.TOOL_ROUNDS_EXHAUSTED, query,
                        "工具请求超出 " + properties.agent().maxToolRounds() + " 轮上限", styleTier));
            }
            answeredByBudgetCheck = true;
        }

        if (rounds == 0 && !toolUsed && intent != null && intent.isAction()) {
            // 小模型经常用自然语言追问槽位而不是发 function call。槽位状态机不能建在"模型肯不肯调工具"上，
            // 否则 maxSlotAsks 与 SLOT_UNRESOLVED 全是摆设：网关自己派生工具、自己抽槽位、自己数追问次数。
            DerivedCall derived = deriveActionCall(intent, query);
            if (derived != null && derived.gap() != null) {
                step(trace, sink, AgentState.TOOL_EXEC,
                        derived.tool() + " missing=" + derived.gap().missingSlots()
                                + " modelArgs=" + traceArgs(derived.slots()));
                return ModelRun.solo(
                        askSlot(session, tenantId, customerId, conversationId, derived.gap(), query, trace, sink, styleTier));
            }
            if (derived != null && derived.missing().isEmpty() && !IdempotencyService.isWrite(derived.tool())) {
                // 读路径且槽位齐备：模型没发 function call 也要把这一枪开了，
                // 不能因为模型偷懒让用户拿不到自己订单的事实。写路径由 deriveActionCall 挡在门外。
                toolUsed = true;
                rounds++;
                LlmTypes.ToolCall derivedCall = new LlmTypes.ToolCall("gateway-derived",
                        derived.tool().apiName(), derived.slots());
                messages.add(LlmTypes.Message.assistant(null, List.of(derivedCall)));
                ToolDispatcher.Dispatch dispatch = dispatcher.dispatch(derivedCall, idempotencyToken);
                step(trace, sink, AgentState.TOOL_EXEC,
                        "gateway-derived " + dispatch.tool() + "=" + dispatch.status()
                                + " modelArgs=" + traceArgs(derivedCall.arguments()));
                sink.toolExecuting(dispatch.tool(), dispatch.label());
                sink.toolResult(dispatch.tool(), dispatch.status(), summarize(dispatch));
                if (dispatch.degraded()) {
                    return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink,
                            FallbackReason.TOOL_UNAVAILABLE, query, dispatch.json(), styleTier));
                }
                messages.add(LlmTypes.Message.tool(derivedCall.id(), dispatch.json()));
            }
        }

        step(trace, sink, AgentState.REPLY, "streaming");
        String answer;
        int promptTokens = 0;
        int completionTokens = 0;
        if (lastReply != null && !lastReply.wantsTool() && lastReply.content() != null
                && (rounds == 0 || answeredByBudgetCheck)) {
            // 首轮就给出内容，或预算检查后模型明确不再要工具：直接把它按打字机切块推出去，不再多打一次模型
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
                        failure.getMessage(), styleTier));
            }
        }

        sessionStore.save(tenantId, customerId, sessionStore.appendTurn(clearPending(session), query, answer));

        List<String> citations = retrieved == null ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::ruleId).toList();
        List<String> citedScopes = retrieved == null ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::scope).toList();

        WriteBackPolicy.Verdict verdict = writeBackPolicy.evaluate(new WriteBackPolicy.Request(
                intent, answer, retrieved != null && !retrieved.empty(), toolUsed,
                retrieved != null && retrieved.degraded(), false));
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
                // 坐标包装在 WriteBackPool.submit 内部完成（票 27）：提交点不再各自记得 wrap
                writeBackPool.submit(() -> {
                    try {
                        cacheService.writeBack(entry, forWrite);
                    } catch (Exception failure) {
                        log.warn("异步写回失败，不影响本次响应: {}", failure.getMessage());
                    }
                });
            }
        } else {
            step(trace, sink, AgentState.CACHE_WRITE, "rejected:" + verdict.reason());
            if (retrieved != null && retrieved.empty() && !retrieved.degraded()
                    && intent != null && intent.cacheAdmissible()) {
                cacheService.writeNegative(tenantId, intent, epoch, lookup);
            } else if (retrieved != null && retrieved.empty() && retrieved.degraded()) {
                // 检索引擎挂了不等于库里没有。这时写负缓存，等于把一次抖动固化成
                // 60 秒的批量转人工——2026-09-08 mix80 压测里就是这样把自己打停的。
                negativeSuppressedCounter.increment();
                step(trace, sink, AgentState.CACHE_WRITE, "negative-suppressed:retrieval-degraded");
            }
        }

        return new ModelRun(new AgentResult(answer, intent, triage.layer(), CacheService.Layer.NONE, citations, trace,
                null, null, false, promptTokens, completionTokens, toolUsed, false, promptCatalog.version()), written);
    }

    /** 缺槽位：追问一次，仍缺则转人工。绝不猜（ADR 0008、ticket 11）。 */
    private AgentResult askSlot(SessionStore.Session session, String tenantId, String customerId,
                                String conversationId, ToolDispatcher.Dispatch dispatch, String query,
                                List<AgentResult.TraceStep> trace, EventSink sink, StyleService.Tier styleTier) {
        int asks = session.slotAskCount() + 1;
        if (asks > properties.agent().maxSlotAsks()) {
            return fallback(AgentState.SLOT_ASK, trace, sink, FallbackReason.SLOT_UNRESOLVED, query,
                    "已追问 " + session.slotAskCount() + " 次仍缺槽位", styleTier);
        }
        String slot = dispatch.missingSlots().get(0);
        String question = dispatcher.question(dispatch.tool(), dispatch.missingSlots());
        step(trace, sink, AgentState.SLOT_ASK, dispatch.tool() + " 缺 " + slot);
        sink.slotAsk(slot, question);
        sessionStore.save(tenantId, customerId, new SessionStore.Session(conversationId, session.turns(),
                dispatch.tool().apiName(), new LinkedHashMap<>(Map.of("askedSlot", slot)), asks));
        return new AgentResult(question, dispatch.tool().intent(), "SLOT", CacheService.Layer.NONE, List.of(), trace,
                null, null, true, 0, 0, false, false, promptCatalog.version());
    }

    /** 网关自己派生出的工具调用：missing 非空即追问，missing 为空且是读工具即可代为执行。 */
    private record DerivedCall(ToolName tool, Map<String, Object> slots, List<String> missing,
                               ToolDispatcher.Dispatch gap) {
    }

    /** 每个工具里"正则能确定判缺"的槽位；不在表里的槽位缺失不触发网关侧追问。 */
    private static final Map<ToolName, List<String>> GATEWAY_CONFIDENT_SLOTS = Map.of(
            ToolName.QUERY_ORDER_DETAIL, List.of("orderNo"),
            ToolName.QUERY_LOGISTICS, List.of("orderNo"),
            ToolName.APPLY_REFUND, List.of("orderNo"),
            ToolName.MODIFY_DELIVERY_ADDRESS, List.of("orderNo", "receiverPhone"));

    /**
     * 模型这一轮只发了自然语言、没发 function call 时，由网关自己派生工具并检查槽位缺口。
     *
     * <p>只接管 GATEWAY_CONFIDENT_SLOTS 里能靠正则定死的那几格：改地址的省/市/区/详址与收件人
     * 必须交给模型抽，用正则判它们缺失等于把用户已经写出来的地址再问一遍。
     *
     * @return null 表示不接管；否则 gap 非空即追问，槽位齐备且是读工具时才允许代为执行
     */
    private DerivedCall deriveActionCall(Intent intent, String query) {
        ToolName tool = ToolName.forIntent(intent);
        if (tool == null) {
            return null;
        }
        Map<String, Object> slots = extractSlots(tool, query);
        List<String> missing = dispatcher.missingSlots(tool, slots);
        // 只对"正则能确定判缺"的槽位发起网关侧追问。改地址有 5 个自由文本槽位，
        // 用正则判它们缺失等于把用户已经写出来的省市区的再问一遍，那种活该交给模型。
        List<String> confident = GATEWAY_CONFIDENT_SLOTS.getOrDefault(tool, List.of());
        List<String> actionable = missing.stream().filter(confident::contains).toList();
        return new DerivedCall(tool, slots, missing,
                actionable.isEmpty() ? null
                        : new ToolDispatcher.Dispatch(tool, null, null, actionable, null, false, false));
    }

    /** 槽位抽取：能正则定死的绝不打模型，这一条同时服务派生路径与续办路径。 */
    private static Map<String, Object> extractSlots(ToolName tool, String query) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?<!\\d)(\\d{5,8})(?!\\d)").matcher(query);
        if (matcher.find()) {
            arguments.put("orderNo", matcher.group(1));
        }
        if (tool == ToolName.MODIFY_DELIVERY_ADDRESS) {
            java.util.regex.Matcher phone =
                    java.util.regex.Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)").matcher(query);
            if (phone.find()) {
                arguments.put("receiverPhone", phone.group(1));
            }
        }
        if (tool == ToolName.APPLY_REFUND && !query.isBlank()) {
            arguments.put("reason", query.length() > 60 ? query.substring(0, 60) : query);
        }
        return arguments;
    }

    /**
     * 上一轮追问过槽位，这一轮把用户补充的信息与原始诉求合并后重放工具。
     * 这里刻意不再进模型：订单号这类槽位用正则就能取，交给模型只会更慢更贵。
     */
    private AgentResult resumePending(SessionStore.Session session, String query, String idempotencyToken,
                                      String tenantId, String customerId, String conversationId,
                                      List<AgentResult.TraceStep> trace, EventSink sink, String systemPrompt,
                                      StyleService.Tier styleTier) {
        step(trace, sink, AgentState.SLOT_ASK, "合并补充信息");
        ToolName tool = ToolName.fromApiName(session.pendingTool());
        if (tool == null) {
            sessionStore.save(tenantId, customerId, clearPending(session));
            return fallback(AgentState.SLOT_ASK, trace, sink, FallbackReason.INTENT_UNRESOLVED, query,
                    "待办工具已失效", styleTier);
        }
        Map<String, Object> arguments = extractSlots(tool, query);
        List<String> missing = dispatcher.missingSlots(tool, arguments);
        if (!missing.isEmpty()) {
            String slot = missing.get(0);
            String question = dispatcher.question(tool, missing);
            int asks = session.slotAskCount() + 1;
            if (asks > properties.agent().maxSlotAsks()) {
                return fallback(AgentState.SLOT_ASK, trace, sink, FallbackReason.SLOT_UNRESOLVED, query, null,
                        styleTier);
            }
            sink.slotAsk(slot, question);
            sessionStore.save(tenantId, customerId, new SessionStore.Session(conversationId, session.turns(),
                    tool.apiName(), arguments, asks));
            return new AgentResult(question, tool.intent(), "SESSION", CacheService.Layer.NONE, List.of(), trace,
                    null, null, true, 0, 0, false, false, promptCatalog.version());
        }
        LlmTypes.ToolCall call = new LlmTypes.ToolCall("resumed", tool.apiName(), arguments);
        ToolDispatcher.Dispatch dispatch = dispatcher.dispatch(call, idempotencyToken);
        step(trace, sink, AgentState.TOOL_EXEC, tool + "=" + dispatch.status());
        if (dispatch.duplicate()) {
            sink.duplicateSubmit(tool, "该请求已处理过，本次未重复执行");
        } else {
            sink.toolExecuting(tool, dispatch.label());
        }
        sink.toolResult(tool, dispatch.status(), summarize(dispatch));
        sessionStore.save(tenantId, customerId, clearPending(session));
        if (dispatch.degraded()) {
            return fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE, query,
                    dispatch.json(), styleTier);
        }
        List<LlmTypes.Message> messages = new ArrayList<>();
        messages.add(LlmTypes.Message.system(systemPrompt));
        messages.add(LlmTypes.Message.user(query + "\n\n【业务系统返回】\n" + dispatch.json()));
        try {
            LlmTypes.Reply reply = llm.stream(new LlmTypes.Request(messages, List.of(),
                    properties.llm().temperature()), sink::token);
            sessionStore.save(tenantId, customerId,
                    sessionStore.appendTurn(clearPending(session), query, reply.content()));
            return new AgentResult(reply.content(), tool.intent(), "SESSION", CacheService.Layer.NONE, List.of(),
                    trace, null, null, false, reply.promptTokens(), reply.completionTokens(), true, false,
                    promptCatalog.version());
        } catch (LlmException failure) {
            return fallback(AgentState.REPLY, trace, sink, mapLlmFailure(failure), query, failure.getMessage(),
                    styleTier);
        }
    }

    private AgentResult fallback(AgentState from, List<AgentResult.TraceStep> trace, EventSink sink,
                                 FallbackReason reason, String query, String detail, StyleService.Tier styleTier) {
        step(trace, sink, AgentState.FALLBACK, reason.name() + (detail == null ? "" : " " + detail));
        // 情绪升级单进人工队列时带 high 优先级（ADR 0034）；其余降级按原口径排队
        Optional<String> ticket = reason == FallbackReason.EMOTION_ESCALATION
                ? fallbackService.escalate(reason, query, detail, "high")
                : fallbackService.escalate(reason, query, detail);
        sink.fallback(reason, ticket.orElse(null));
        // 话术选择随风格档位联动（ADR 0038）：FRIENDLY 档在话术前加安抚短句，不改话术生成
        String answer = styleService.fallbackPrefix(styleTier) + reason.userMessage()
                + ticket.map(id -> "（工单号 " + id + "）").orElse("");
        sink.token(answer);
        return new AgentResult(answer, Intent.ESCALATE, "FALLBACK", CacheService.Layer.NONE, List.of(), trace,
                reason, ticket.orElse(null), false, 0, 0, false, true, promptCatalog.version());
    }

    /**
     * 按意图决定下发哪些工具（ADR 0007：意图枚举是唯一驱动源）。
     *
     * <p>政策咨询与转人工一律不下发工具：实测给了工具，小模型会凭空编一个订单号去查，
     * 把一句政策咨询答成"查不到您的订单"。落到任一动作意图时下发全部四个业务工具，让工具选择本身充当
     * 意图证据——dev 评测（DashScope qwen-plus，180 条）实测按子意图裁到单个工具，会把 T0 的路由误差
     * 放大成选错工具：判成 ACTION_ORDER 的改地址请求只拿到 queryOrderDetail，模型没有选对的机会。
     */
    private List<Map<String, Object>> toolsFor(TriageResult triage) {
        Intent intent = triage.intent();
        if (intent == null || intent == Intent.UNKNOWN) {
            return ToolContracts.actionDescriptors();
        }
        return intent.isAction() ? ToolContracts.actionDescriptors() : List.of();
    }

    /** 只有两个写操作意图需要状态机记住"必须真的办掉"；查询类答错顶多是信息不全，不会造成业务后果。 */
    private static ToolName expectedWriteTool(Intent intent) {
        if (intent == null) {
            return null;
        }
        return switch (intent) {
            case ACTION_REFUND -> ToolName.APPLY_REFUND;
            case ACTION_ADDRESS -> ToolName.MODIFY_DELIVERY_ADDRESS;
            default -> null;
        };
    }

    /**
     * 步骤失败的判定（ADR 0036 的中止触发面）：业务性失败与不可用都算，幂等重放不算。
     * 超时/不可用多数已由降级路径提前兜住，这里主要接住 NOT_FOUND 与 STATE_NOT_ALLOWED——
     * 两者正是 part6 两条"前步失败即中止"用例的原因。
     */
    private static boolean failedStep(ToolStatus status) {
        return status == ToolStatus.NOT_FOUND || status == ToolStatus.STATE_NOT_ALLOWED
                || status == ToolStatus.TIMEOUT || status == ToolStatus.UNAVAILABLE;
    }

    /** 包级可见只为让降级映射进用例（ticket 14）；三类模型失败必须各自映射到不同 reason。 */
    static FallbackReason mapLlmFailure(LlmException failure) {
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

    /** trace 里记的是模型自己抽出来的参数，不是工具返回体：评测要量的是前者。 */
    private static String traceArgs(java.util.Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return TRACE_JSON.writeValueAsString(arguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            return String.valueOf(arguments);
        }
    }

    private static void step(List<AgentResult.TraceStep> trace, EventSink sink, AgentState state, String detail) {
        trace.add(new AgentResult.TraceStep(state.name(), System.currentTimeMillis(), detail));
        sink.status(state, detail);
    }
}
