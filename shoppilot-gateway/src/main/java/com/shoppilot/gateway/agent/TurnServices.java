package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.cache.SingleFlight;
import com.shoppilot.gateway.cache.WriteBackPolicy;
import com.shoppilot.gateway.cache.WriteBackPool;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.config.TenantDomainRegistry;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.llm.LlmGateway;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.orchestration.HandoffTrigger;
import com.shoppilot.gateway.sentiment.SentimentGate;
import com.shoppilot.gateway.store.StoreProfileService;
import com.shoppilot.gateway.style.StyleService;
import com.shoppilot.gateway.triage.TriageEngine;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolName;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 编排共享运行态（round48 票 203）。
 *
 * <p>依赖、指标与跨 handler 的出口集中在这里：{@code fallback} 的工单/话术/trace 顺序、token 账本、
 * 统一 {@code step} 与工具结果摘要。只做搬运，不改任何判定——判据仍在状态机/工具派发/缓存层。
 * 字段包级可见只为让同包的四个 handler 读取，不对外暴露。
 */
final class TurnServices {

    private static final Logger log = LoggerFactory.getLogger(TurnServices.class);

    static final com.fasterxml.jackson.databind.ObjectMapper TRACE_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    final TriageEngine triageEngine;
    final CacheService cacheService;
    final SingleFlight singleFlight;
    final WriteBackPolicy writeBackPolicy;
    final KbEpoch kbEpoch;
    final TenantDomainRegistry domainRegistry;
    final HybridRetriever retriever;
    final LlmGateway llm;
    final ToolDispatcher dispatcher;
    final SessionStore sessionStore;
    final FallbackService fallbackService;
    final GatewayProperties properties;
    final WriteBackPool writeBackPool;
    final PromptCatalog promptCatalog;
    final SentimentGate sentimentGate;
    final StyleService styleService;
    final StoreProfileService storeProfileService;
    final MeterRegistry registry;
    final Counter toolRoundExhaustedCounter;
    final Counter negativeSuppressedCounter;
    final Counter writeNudgeCounter;
    final Counter multiToolCallCounter;
    final Counter reflectionTriggeredCounter;
    final Counter reflectionExhaustedCounter;
    final HandoffTrigger handoffTrigger = new HandoffTrigger();
    final Timer handoffDecisionTimer;
    final AtomicLong handoffDecisionMaxNanos = new AtomicLong();

    TurnServices(TriageEngine triageEngine, CacheService cacheService, SingleFlight singleFlight,
                 WriteBackPolicy writeBackPolicy, KbEpoch kbEpoch, TenantDomainRegistry domainRegistry,
                 HybridRetriever retriever, LlmGateway llm, ToolDispatcher dispatcher,
                 SessionStore sessionStore, FallbackService fallbackService, GatewayProperties properties,
                 WriteBackPool writeBackPool, PromptCatalog promptCatalog, SentimentGate sentimentGate,
                 StyleService styleService, StoreProfileService storeProfileService, MeterRegistry registry) {
        this.triageEngine = triageEngine;
        this.cacheService = cacheService;
        this.singleFlight = singleFlight;
        this.writeBackPolicy = writeBackPolicy;
        this.kbEpoch = kbEpoch;
        this.domainRegistry = domainRegistry;
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
        this.storeProfileService = storeProfileService;
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
        // 反思的三态靠一个名字的两个标签值判（round35 票 145 / 票 139）：triggered = 真的发起了
        // 一次反思重试；exhausted = 同一工具的配额已用掉之后又一次③失败，按原语义收口落工单。
        // 写工具与"没有轮预算"两种情况都不落在这两格里（前者不在反思范围、后者是预算边界）。
        this.reflectionTriggeredCounter = Counter.builder("shoppilot_reflection_total")
                .tag("outcome", "triggered").register(registry);
        this.reflectionExhaustedCounter = Counter.builder("shoppilot_reflection_total")
                .tag("outcome", "exhausted").register(registry);
        // H4：sidecar 的口径源。都不是 LLM 调用；blocked reason / friction 只进指标与工单，不改路由。
        this.handoffDecisionTimer = Timer.builder("shoppilot_handoff_decision_seconds")
                .description("Handoff 触发器判定耗时")
                // 触发器是纯规则/图查询，正常在微秒级；桶按毫秒量级钉住，
                // 否则默认秒级桶会把退化压进 100ms/250ms，告警失去区分度。
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(5),
                        java.time.Duration.ofMillis(10), java.time.Duration.ofMillis(25),
                        java.time.Duration.ofMillis(50), java.time.Duration.ofMillis(100),
                        java.time.Duration.ofMillis(250))
                .register(registry);
        // Timer.max() 走的是 TimeWindowMax：空闲一个时间窗就归零，而均值是进程累计值，
        // 于是报表会出现「均值 0.06ms、最大 0.00ms」。累计最大单独记一份（round47 票 198）。
        Gauge.builder("shoppilot_handoff_decision_seconds_max", handoffDecisionMaxNanos,
                        nanos -> nanos.get() / 1_000_000_000d)
                .description("Handoff 触发器判定耗时的进程内累计最大值（秒）")
                .register(registry);
    }

    // ── 出口 ──────────────────────────────────────────────────────────────

    AgentResult fallback(AgentState from, List<AgentResult.TraceStep> trace, EventSink sink,
                         FallbackReason reason, String query, String detail, StyleService.Tier styleTier,
                         TokenUsage usage) {
        step(trace, sink, AgentState.FALLBACK, reason.name() + (detail == null ? "" : " " + detail));
        // H4 friction：工具真的拿到了结果但本轮仍降级。它不自动改路由，只给 sidecar 找"修好却留不住人"的模式。
        if (toolSucceededInTrace(trace)) {
            registry.counter("shoppilot_quality_tool_success_fallback_total").increment();
        }
        // 情绪升级单进人工队列时带 high 优先级（ADR 0034）；其余降级按原口径排队
        Optional<String> ticket = reason == FallbackReason.EMOTION_ESCALATION
                ? fallbackService.escalate(reason, query, detail, "high")
                : fallbackService.escalate(reason, query, detail);
        sink.fallback(reason, ticket.orElse(null));
        // 话术选择随风格档位联动（ADR 0038）：FRIENDLY 档在话术前加安抚短句，不改话术生成
        String answer = styleService.fallbackPrefix(styleTier) + reason.userMessage()
                + ticket.map(id -> "（工单号 " + id + "）").orElse("");
        sink.token(answer);
        // 降级出口也回填已经花掉的轮次（票 145 / R12 的第二处缺口）：此前恒报 0，
        // 而"办了 4 轮最后降级"的请求其实已经付过钱了。
        return new AgentResult(answer, Intent.ESCALATE, "FALLBACK", CacheService.Layer.NONE, List.of(), trace,
                reason, ticket.orElse(null), false, usage.prompt(), usage.completion(), false, true,
                promptCatalog.version(), List.of(), AgentResult.ContextComposition.NONE, List.of());
    }

    // ── 共用小工具 ────────────────────────────────────────────────────────

    /** H4 friction：只认确实拿到结果的工具步；失败 / retry / 拦截不算“修好但仍留不住人”。 */
    private static boolean toolSucceededInTrace(List<AgentResult.TraceStep> trace) {
        return trace.stream()
                .filter(step -> AgentState.TOOL_EXEC.name().equals(step.state()))
                .map(AgentResult.TraceStep::detail)
                .filter(detail -> detail != null)
                .anyMatch(detail -> detail.endsWith("=OK")
                        || detail.endsWith("=IDEMPOTENT_REPLAY")
                        || detail.endsWith("=PENDING_APPROVAL"));
    }

    /** 包级可见只为让降级映射进用例（ticket 14）；三类模型失败必须各自映射到不同 reason。 */
    static FallbackReason mapLlmFailure(com.shoppilot.gateway.llm.LlmException failure) {
        return switch (failure.kind()) {
            case TIMEOUT -> FallbackReason.LLM_TIMEOUT;
            case UNAVAILABLE -> FallbackReason.LLM_CIRCUIT_OPEN;
            case BUDGET_EXCEEDED -> FallbackReason.LLM_BUDGET_EXCEEDED;
        };
    }

    static void appendHistory(List<LlmTypes.Message> messages, SessionStore.Session session) {
        if (session.turns() == null) {
            return;
        }
        for (SessionStore.Turn turn : session.turns()) {
            messages.add(new LlmTypes.Message(turn.role(), turn.text(), null, List.of()));
        }
    }

    static SessionStore.Session clearPending(SessionStore.Session session) {
        return new SessionStore.Session(session.conversationId(), session.turns(), null, new LinkedHashMap<>(), 0,
                session.currentRole(), session.handoffTrail());
    }

    static String summarize(ToolDispatcher.Dispatch dispatch) {
        return dispatch.status() == null ? "无结果" : dispatch.status().name();
    }

    /** trace 里记的是模型自己抽出来的参数，不是工具返回体：评测要量的是前者。 */
    static String traceArgs(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return TRACE_JSON.writeValueAsString(arguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            return String.valueOf(arguments);
        }
    }

    static void step(List<AgentResult.TraceStep> trace, EventSink sink, AgentState state, String detail) {
        trace.add(new AgentResult.TraceStep(state.name(), System.currentTimeMillis(), detail));
        sink.status(state, detail);
    }

    /** 检索抖动只记日志，不让一次故障变成批量转人工（原 runModelPath 里的同一行）。 */
    static void warnRetrievalFailure(RuntimeException failure) {
        log.warn("混合检索失败，本轮无政策上下文: {}", failure.getMessage());
    }

    // ── 反思配额（round35 票 144 / 票 138；round48 票 203 搬入） ──────────

    /**
     * 反思是否触发（round35 票 144 / 票 138 规则一、三、四）。③操作性失败由调用点保证（
     * {@link ToolDispatcher.Dispatch#degraded()}）；这里只问三件事：
     *
     * <p>① **是读工具**——写工具（{@code applyRefund} / {@code modifyDeliveryAddress}）的③失败一律按原语义
     * 收口：结果不明时自动重试资金动作风险大于收益，而改参重试还会绕开两层幂等（派生 token 含参数，
     * 新参 = 新动作）；
     * <p>② **这个工具还没用掉那次配额**——键是 {@link ToolName}，同一工具的任何③类失败合计 1 次
     * （比"同型失败"更严、更简单、更难绕）；
     * <p>③ **失败的那一枪之后还有轮预算**——{@code rounds} 已经把失败枪算进去了，所以这里就是
     * "反思还占得起一轮"的判据（反思占轮、不加轮次）。
     *
     * <p>闸判定不在这里：①闸、⓪非失败、幂等回放与模型输出层错误各自在原分支上收口，重试也要重过全套闸。
     */
    boolean reflectionEligible(ToolName tool, int rounds, java.util.Set<ToolName> reflectedTools) {
        return tool != null
                && !IdempotencyService.isWrite(tool)
                && !reflectedTools.contains(tool)
                && rounds < properties.agent().maxToolRounds();
    }

    /**
     * 反思的观测面（round35 票 145 / 票 139 的字段清单）：counter 的 {@code triggered} 格 + trace 的小写头行。
     *
     * <p>**头必须小写**：gold 的 {@code executed_tools} 只收「大写且无空格」的头（{@code corrective=} 同例），
     * 写成大写会把工具序列判红。{@code attempt} 记的是**这一枪重试的序号**（失败枪 + 1），
     * 与 {@code PlanStep.attempt} 同一个数法——所以反思重试成功的那一步能读到 attempt=2 且 reflection=true。
     *
     * <p>挂 {@link AgentState#PLAN}：反思是系统在规划层做的动作，不是又一次工具执行。
     */
    void noteReflection(ToolName tool, int attempt, List<AgentResult.TraceStep> trace, EventSink sink) {
        reflectionTriggeredCounter.increment();
        step(trace, sink, AgentState.PLAN, "reflection=" + tool.apiName() + " attempt=" + attempt);
    }

    /**
     * 配额用尽那一格（票 139 的三态判据：同工具第二次③失败）：counter 的 {@code exhausted} +1。
     *
     * <p>写工具与"没有轮预算"两种情况**都不计数**——前者不在反思范围内（票 138 规则四），
     * 后者是预算边界、不是配额用尽，两格都不该被它们灌进来。
     */
    void noteReflectionExhausted(ToolName tool, java.util.Set<ToolName> reflectedTools) {
        if (tool != null && !IdempotencyService.isWrite(tool) && reflectedTools.contains(tool)) {
            reflectionExhaustedCounter.increment();
        }
    }
}
