package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheEntry;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.cache.QueryNormalizer;
import com.shoppilot.gateway.cache.SingleFlight;
import com.shoppilot.gateway.cache.WriteBackPolicy;
import com.shoppilot.gateway.cache.WriteBackPool;
import com.shoppilot.gateway.channel.ChannelContext;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.config.TenantDomainRegistry;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.knowledge.Domain;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 有界 Agent 状态机（ADR 0008）。
 *
 * <p>工具循环有硬上限（round35 票 143 起默认 4 轮；ADR 0069 §三 换代）：脱离时延预算谈自由 agent
 * 循环，在这个场景里是自杀式设计。上限仍是硬约束——超限照旧 {@code TOOL_ROUNDS_EXHAUSTED} 落工单。
 */
@Component
public class AgentStateMachine {

    private static final com.fasterxml.jackson.databind.ObjectMapper TRACE_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(AgentStateMachine.class);

    /** 单条条款注入上限：防止一次检索把 Prompt 撑爆，也保证 5 条条款可控。 */
    private static final int MAX_CLAUSE_CHARS = 600;

    /**
     * 反思的失败上下文（round35 票 144 / 票 138 规则二）。只陈述"这一枪没拿到结果"这个事实，
     * 并给出下一步的做法；**不教模型给失败分类**——闸 / 中止 / 反思的判定全在状态机里
     * （ADR 0069 已否决"把反思做成提示词驱动"：分类一旦进提示词，等于给模型讨价还价的空间）。
     */
    private static final String REFLECTION_CONTEXT =
            "【系统提示】上一次工具调用没有拿到业务结果（临时故障，不代表订单事实）。"
                    + "换一种做法（换工具或换参数）再试一次；也可以直接用已有信息作答。";

    private final TriageEngine triageEngine;
    private final CacheService cacheService;
    private final SingleFlight singleFlight;
    private final WriteBackPolicy writeBackPolicy;
    private final KbEpoch kbEpoch;
    private final TenantDomainRegistry domainRegistry;
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
    private final Counter reflectionTriggeredCounter;
    private final Counter reflectionExhaustedCounter;

    public AgentStateMachine(TriageEngine triageEngine, CacheService cacheService, SingleFlight singleFlight,
                             WriteBackPolicy writeBackPolicy, KbEpoch kbEpoch, TenantDomainRegistry domainRegistry,
                             HybridRetriever retriever,
                             LlmGateway llm, ToolDispatcher dispatcher, SessionStore sessionStore,
                             FallbackService fallbackService, GatewayProperties properties,
                             WriteBackPool writeBackPool, PromptCatalog promptCatalog, SentimentGate sentimentGate,
                             StyleService styleService, MeterRegistry registry) {
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
    }

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
    private static final class TokenUsage {

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
        // 行业是**显式登记**出来的，不是从店铺名或问句猜的（ADR 0067）。未登记租户 fail-closed 成
        // UNKNOWN：检索只看本租户规则，缓存平台桶按 UNKNOWN 分区。这一格同时喂给意图判定、检索、
        // 缓存与工具裁剪四处，是 round33 修跨行业串答案的唯一来源。
        Domain domain = domainRegistry.resolve(tenantId);

        // 情绪门（ADR 0034）：INTAKE → TRIAGE 之间，词典层 0 token。高情绪在这里直接落工单转人工，
        // 不让激动的买家走完检索/模型/工具全链路才被转接——等待本身就是二次激怒。
        // 状态机 10 状态不扩：情绪判定发生在 INTAKE 状态内部；perf 口径下第二层 LLM 分类不启用。
        SentimentGate.Verdict sentiment = sentimentGate.evaluate(query);
        step(trace, sink, AgentState.INTAKE, "sentiment=" + sentiment.emotion() + " via " + sentiment.source());
        // 本请求的 LLM 用量账本（票 145 / R12）：入口层这一跳先入账——它连缓存命中也要付钱，
        // 而它在缓存查询之前，所以必须在任何出口之前就开好这本账。
        TokenUsage usage = new TokenUsage(sentiment.promptTokens(), sentiment.completionTokens());
        // 风格档位（ADR 0038）：channel × emotion × intent → 档位，注入段拼在版本化基座之后；
        // 回答正文仍由同一次 LLM 调用产出。档位随参数穿透到各降级出口，话术选择随档位联动。
        StyleService.Tier styleTier = styleService.tierFor(ChannelContext.current(), sentiment.emotion(), null);
        registry.counter("shoppilot_style_applied_total", "style", styleTier.name()).increment();
        // 档位同时进 trace（与 sentiment= 同构）：离线评测与验收脚本按同步响应就能归因提示词形态，
        // 不必为它新增响应字段（票 35 登记的"离线归因"触发条件由此关闭）
        step(trace, sink, AgentState.INTAKE, "style=" + styleTier.name());
        sink.style(styleTier.name());
        String systemPrompt = styleService.assemble(promptCatalog.systemPrompt(), styleTier);
        // 显式转人工优先于情绪判定（ADR 0042）：ADR 0017 把「转人工」下沉到 T0 就是为了不依赖任何
        // 服务，而情绪门位于 INTAKE、先于意图判定，会把这条平静问句判成 URGENT 后抢先转接，于是 T0
        // 那张含「转人工」的词表根本没机会执行（2026-09-20 矩阵 F2；2026-09-23 活体验收 step 7 复现）。
        // 命中显式升级词表时不走情绪短路，放行到 triage，由既有 USER_REQUESTED 出口收口。
        if (sentiment.escalated() && !triageEngine.isExplicitEscalation(query)) {
            sink.meta(conversationId, null, CacheService.Layer.NONE);
            return fallback(AgentState.INTAKE, trace, sink, FallbackReason.EMOTION_ESCALATION, query,
                    "emotion=" + sentiment.emotion() + " via " + sentiment.source(), styleTier, usage);
        }

        String requestFingerprint = requestFingerprint(query, idempotencyToken);

        // 请求级幂等回放（ADR 0046 票 58）：客户端带着同一个 token 重试**同一次请求**时，回放首次结果，
        // 不打模型、也不重复执行。放这一位置有两条理由：① 不扰动上面那道情绪门的既有优先级（ADR 0034/0042
        // 的排序是两次回归换来的，回放不该抢在安全出口之前）；② 放在 hasPending 之前——回放不是槽位补齐。
        // 它刻意**不**走 fallback 出口：回放不是降级，落工单会污染「降级 9」的口径（ADR 0009/0046）。
        if (requestFingerprint != null) {
            Optional<IdempotencyService.Replay> replay =
                    dispatcher.lookupReplay(idempotencyToken.trim(), requestFingerprint);
            if (replay.isPresent()) {
                return replayAnswer(replay.get(), session, query, sink, trace, tenantId, customerId, conversationId,
                        usage);
            }
        }

        if (session.hasPending()) {
            sink.meta(conversationId, null, CacheService.Layer.NONE);
            return resumePending(session, query, idempotencyToken, tenantId, customerId, conversationId, trace, sink,
                    systemPrompt, styleTier, usage);
        }

        step(trace, sink, AgentState.TRIAGE, "开始判定");
        TriageEngine.Outcome outcome = triageEngine.triage(query, domain);
        TriageResult triage = outcome.result();
        step(trace, sink, AgentState.TRIAGE,
                "layer=" + triage.layer() + " intent=" + triage.intent() + " admissible=" + triage.cacheAdmissible());

        if (triage.intent() == Intent.ESCALATE) {
            sink.meta(conversationId, Intent.ESCALATE, CacheService.Layer.NONE);
            return fallback(AgentState.TRIAGE, trace, sink, FallbackReason.USER_REQUESTED, query, null, styleTier,
                    usage);
        }

        if (triage.cacheAdmissible()) {
            step(trace, sink, AgentState.CACHE_READ, "intent=" + triage.intent());
            CacheService.Lookup lookup = cacheService.lookup(tenantId, domain, triage.intent(), query, epoch,
                    outcome.queryVector());
            if (lookup.hit()) {
                sink.meta(conversationId, triage.intent(), lookup.layer());
                step(trace, sink, AgentState.REPLY, "cache=" + lookup.layer());
                CacheEntry entry = lookup.entry().get();
                sink.token(entry.answer());
                // 票 49 的不变量「context.ruleIds 与 citations 同源同序」在命中路径同样成立：
                // 缓存条目随带引用（ADR 0003），这里把它镜像进观测字段。另外两格照实为零——
                // 命中路径没有 Prompt，所以没有历史轮、也没有估出来的 token 数。
                // 但 token 字段**不是零**（票 145 / R12 的第三处缺口）：这一跳入口层的情绪分类已经付过钱，
                // 账本里就记着它。
                return new AgentResult(entry.answer(), triage.intent(), triage.layer(), lookup.layer(),
                        entry.sourceRuleIds(), trace, null, null, false, usage.prompt(), usage.completion(),
                        false, false, promptCatalog.version(), List.of(),
                        new AgentResult.ContextComposition(List.copyOf(entry.sourceRuleIds()), 0, 0),
                        citationDetailsOf(entry.sourceRuleIds(), entry.sourceHeadings(), entry.sourceDocs()));
            }
            if (lookup.negative()) {
                sink.meta(conversationId, triage.intent(), CacheService.Layer.NONE);
                step(trace, sink, AgentState.CACHE_READ, "negative-marker");
                return fallback(AgentState.CACHE_READ, trace, sink, FallbackReason.INTENT_UNRESOLVED, query,
                        "该问题此前已确认无对应政策条款", styleTier, usage);
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
                                usage.prompt(), usage.completion(), false, false, promptCatalog.version(),
                                List.of(),
                                AgentResult.ContextComposition.NONE,
                                citationDetailsOf(entry.sourceRuleIds(), entry.sourceHeadings(), entry.sourceDocs())))
                        .orElseGet(() -> fallback(AgentState.CACHE_READ, trace, sink,
                                FallbackReason.INTENT_UNRESOLVED, query, null, styleTier, usage));
            }
            boolean published = false;
            try {
                sink.meta(conversationId, triage.intent(), CacheService.Layer.NONE);
                ModelRun run = runModelPath(triage, lookup, query, idempotencyToken, tenantId, customerId,
                        conversationId, session, epoch, domain, trace, sink, systemPrompt, styleTier, usage);
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
                conversationId, session, epoch, domain, trace, sink, systemPrompt, styleTier, usage).result();
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
                                  String conversationId, SessionStore.Session session, long epoch, Domain domain,
                                  List<AgentResult.TraceStep> trace, EventSink sink, String systemPrompt,
                                  StyleService.Tier styleTier, TokenUsage usage) {
        Intent intent = triage.intent();
        List<LlmTypes.Message> messages = new ArrayList<>();
        messages.add(LlmTypes.Message.system(systemPrompt));
        appendHistory(messages, session);

        HybridRetriever.Result retrieved = null;
        if (intent == null || intent == Intent.UNKNOWN || intent.isPolicy()) {
            step(trace, sink, AgentState.RETRIEVE, "intent=" + intent);
            try {
                retrieved = retriever.retrieve(query, tenantId, domain, intent == Intent.UNKNOWN ? null : intent);
            } catch (RuntimeException retrievalFailure) {
                log.warn("混合检索失败，本轮无政策上下文: {}", retrievalFailure.getMessage());
                retrieved = HybridRetriever.Result.unavailable(epoch);
            }
            step(trace, sink, AgentState.RETRIEVE, "dense=" + retrieved.denseHits() + " lexical=" + retrieved.lexicalHits()
                    + " fused=" + retrieved.rules().size() + " degraded=" + retrieved.degraded());
        }
        // 空召回不是答案（2026-10-17 实机复现后定：与跨店同问同答同批修复）。
        // 政策意图在可见范围里检索不到任何条款时，此前把模型自由发挥的正文直接当答案返回——
        // 酒店租户会编出"死蟹全额退款""无理由退货 15 天"这类本店并不存在的承诺，零引用、无降级、
        // 不落工单；同一句第二次问才因负缓存转人工，首问与复问的口径自相矛盾。这与 ADR 0009
        // 「办不成就给一句实话并落可查工单」、ADR 0067「宁可转人工，不可拿另一个行业的条款回答」
        // 直接冲突。改成与负缓存同一出口：确定性转人工 + 落单 + 写负标记，模型不再有机会发挥。
        // 只在"跑完了、确实 0 条"时触发；检索降级（degraded）仍走原路——把一次抖动固化成
        // 批量转人工是 2026-09-08 压测里踩过的坑，不在本出口的范围内。
        if (intent != null && intent.isPolicy() && retrieved != null && retrieved.empty()
                && !retrieved.degraded()) {
            if (lookup.normalizedQuery() != null && !lookup.normalizedQuery().isEmpty()) {
                cacheService.writeNegative(tenantId, domain, intent, epoch, lookup);
            }
            step(trace, sink, AgentState.RETRIEVE, "empty-recall:可见范围内无该意图条款");
            return ModelRun.solo(fallback(AgentState.RETRIEVE, trace, sink, FallbackReason.INTENT_UNRESOLVED,
                    query, "可见范围内没有该诉求的政策条款（空召回）", styleTier, usage));
        }
        messages.add(LlmTypes.Message.user(composeUserMessage(query, retrieved)));

        boolean toolUsed = false;
        int rounds = 0;
        // 用户明确要求办理的写动作：状态机记得它必须真的执行过，一句文字承诺不算办完。
        // **行业门也管这一格**（round35 票 146 / R11）：非电商租户的 toolsFor 是空集，模型手上根本没有
        // applyRefund / modifyDeliveryAddress 可调；这时再纠偏就是给模型递一句「直接调用 applyRefund 把它
        // 真的办掉」，与提示词第 7 条「无工具时照实说明办不了」冲突，还诱导它谎报「已提交」。
        // 同一道行业门在 toolsFor 与网关派生两处已经吃过，守卫这里是**漏吃**（ADR 0031 第 10 行的事实性修正）。
        ToolName expectedWrite = domain == Domain.ECOMMERCE ? expectedWriteTool(intent) : null;
        boolean expectedWriteDone = false;
        boolean nudged = false;
        boolean answeredByBudgetCheck = false;
        // 计划（ADR 0036）：按执行顺序留存每步结果 JSON，供后步参数表达式取值；中止标记让指标分账
        List<String> stepResults = new ArrayList<>();
        // 同一批执行事实的可输出副本（票 48）：ADR 0036 的判定逻辑一行没动，这里只是把"执行过什么"记下来
        List<AgentResult.PlanStep> planSteps = new ArrayList<>();
        boolean planAborted = false;
        LlmTypes.Reply lastReply = null;
        // 反思配额（round35 票 144 / 票 138 规则三）：键 = ToolName，同一工具的任何③类失败**合计 1 次**。
        // 作用域 = 本次请求；第二次③失败（无论 TIMEOUT 还是 UNAVAILABLE）按原语义收口。
        Set<ToolName> reflectedTools = new HashSet<>();
        // 观测面的两本账（round35 票 145 / 票 139）：attempt 记「该工具的第几枪」（含失败的那一枪，
        // 所以反思重试成功的那一步是 attempt=2）；pendingReflections 标出「下一枪是反思重试」，
        // 供 plan[] 的 reflection 字段在那一枪落账时消费。都只读不改执行语义。
        Map<ToolName, Integer> toolAttempts = new LinkedHashMap<>();
        Set<ToolName> pendingReflections = new HashSet<>();
        while (rounds < properties.agent().maxToolRounds()) {
            step(trace, sink, AgentState.PLAN, "round=" + rounds);
            LlmTypes.Request planRequest = new LlmTypes.Request(messages, toolsFor(triage, domain),
                    properties.llm().temperature());
            try {
                lastReply = llm.complete(planRequest);
            } catch (LlmException failure) {
                return ModelRun.solo(fallback(AgentState.PLAN, trace, sink, mapLlmFailure(failure), query,
                        failure.getMessage(), styleTier, usage));
            }
            usage.add(lastReply);
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
                        query, "参数表达式不合法，已拒绝执行：" + resolution.rejectedExpression(), styleTier, usage));
            }
            LlmTypes.ToolCall call = new LlmTypes.ToolCall(rawCall.id(), rawCall.name(), resolution.arguments());
            messages.add(LlmTypes.Message.assistant(lastReply.content(), List.of(call)));
            long dispatchStarted = System.nanoTime();
            ToolDispatcher.Dispatch dispatch = dispatcher.dispatch(call, idempotencyToken,
                    dialogueText(query, session), requestFingerprint(query, idempotencyToken));
            long dispatchMillis = (System.nanoTime() - dispatchStarted) / 1_000_000L;
            if (dispatch.unknown()) {
                // 模型编出了不存在的工具：不拿 null 工具往下走，直接兜底
                return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE,
                        query, "未注册的工具 " + call.name(), styleTier, usage));
            }
            if (dispatch.fabricated()) {
                // 模型凭空编了一个订单号：绝不拿它去撞库，退回来向用户追问合法订单号
                step(trace, sink, AgentState.TOOL_EXEC,
                        "fabricated-orderNo 已拦截 modelArgs=" + traceArgs(call.arguments()));
                return ModelRun.solo(askSlot(session, tenantId, customerId, conversationId, dispatch, query, trace,
                        sink, styleTier, usage));
            }
            if (dispatch.needsSlot()) {
                step(trace, sink, AgentState.TOOL_EXEC,
                        dispatch.tool() + " missing=" + dispatch.missingSlots()
                                + " modelArgs=" + traceArgs(call.arguments()));
                return ModelRun.solo(askSlot(session, tenantId, customerId, conversationId, dispatch, query, trace,
                        sink, styleTier, usage));
            }
            // 走到这里这一枪真的派发了：记它是该工具的第几枪（票 145 的 attempt）。
            // 失败的那一枪也算——否则反思重试成功的那一步会被记成 attempt=1，与「反思回合 attempt=2」对不上。
            int attempt = toolAttempts.merge(dispatch.tool(), 1, Integer::sum);
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
                // ③操作性失败（票 138 规则一/二）：读工具、这个工具还没用掉那次配额、且失败枪之后还有轮预算
                // → 把失败事实回填（与上面那条 assistant 的 tool_call 配对）加一条失败上下文，进下一轮重规划。
                // 这一枪**不进** stepResults/plan[]：plan 记的是真的办成的步（票 48 契约），也是
                // eval_task.py「取第一个同名工具步」这条不变量成立的前提。
                // 闸判定先于这里：①闸（NOT_FOUND / STATE_NOT_ALLOWED）、⓪受理、幂等回放、编造拦截与
                // 模型输出层错误都在别的分支上收口——反思够不到它们，重试也照过全套闸。
                if (reflectionEligible(dispatch.tool(), rounds, reflectedTools)) {
                    reflectedTools.add(dispatch.tool());
                    pendingReflections.add(dispatch.tool());
                    noteReflection(dispatch.tool(), attempt + 1, trace, sink);
                    messages.add(LlmTypes.Message.tool(call.id(), dispatch.json()));
                    messages.add(LlmTypes.Message.user(REFLECTION_CONTEXT));
                    continue;
                }
                noteReflectionExhausted(dispatch.tool(), reflectedTools);
                return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE,
                        query, dispatch.json(), styleTier, usage));
            }
            stepResults.add(dispatch.json());
            // 反思印记按「同一工具的下一枪」消费（票 139：reflection = 本步是反思重试）。模型要是换了路
            // （换工具的反思是提示词明说允许的），这笔待办就作废——否则同一工具后来一次无关的正常调用
            // 会被打上 reflection=true / attempt=2 的假印记。
            boolean reflectionRetry = pendingReflections.remove(dispatch.tool());
            if (!reflectionRetry) {
                pendingReflections.clear();
            }
            planSteps.add(new AgentResult.PlanStep(
                    dispatch.tool() == null ? call.name() : dispatch.tool().apiName(),
                    dispatch.status() == null ? "UNKNOWN" : dispatch.status().name(),
                    dispatchMillis, resolution.arguments(), attempt, reflectionRetry));
            if (dispatch.status() == ToolStatus.PENDING_APPROVAL) {
                // 受理即收尾（ADR 0047 票 59）：资金动作已受理、等待人工审核，不打第二跳模型。
                // 受理不是失败，不走 fallback 出口——落工单会污染「降级 9」的口径与 ADR 0009 的语义。
                return ModelRun.solo(pendingApprovalAnswer(dispatch, session, query, sink, trace, tenantId,
                        customerId, triage.layer(), List.copyOf(planSteps), usage));
            }
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
                        "轮次上限内未能完成 " + expectedWrite.apiName(), styleTier, usage));
            }
            step(trace, sink, AgentState.PLAN, "budget-check");
            LlmTypes.Request budgetRequest = new LlmTypes.Request(messages, toolsFor(triage, domain),
                    properties.llm().temperature());
            try {
                lastReply = llm.complete(budgetRequest);
            } catch (LlmException failure) {
                return ModelRun.solo(fallback(AgentState.PLAN, trace, sink, mapLlmFailure(failure), query,
                        failure.getMessage(), styleTier, usage));
            }
            usage.add(lastReply);
            if (lastReply.wantsTool()) {
                toolRoundExhaustedCounter.increment();
                step(trace, sink, AgentState.PLAN, "tool-rounds-exhausted");
                return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink,
                        FallbackReason.TOOL_ROUNDS_EXHAUSTED, query,
                        "工具请求超出 " + properties.agent().maxToolRounds() + " 轮上限", styleTier, usage));
            }
            answeredByBudgetCheck = true;
        }

        // 行业门同样管住网关派生这一枪（ADR 0067）：酒店租户即使被判成动作意图也不许由网关
        // 代为派发电商读工具——否则"酒店不碰电商工具"会从 toolsFor 那道门旁边绕过去。
        if (rounds == 0 && !toolUsed && intent != null && intent.isAction() && domain == Domain.ECOMMERCE) {
            // 小模型经常用自然语言追问槽位而不是发 function call。槽位状态机不能建在"模型肯不肯调工具"上，
            // 否则 maxSlotAsks 与 SLOT_UNRESOLVED 全是摆设：网关自己派生工具、自己抽槽位、自己数追问次数。
            DerivedCall derived = deriveActionCall(intent, query);
            if (derived != null && derived.gap() != null) {
                step(trace, sink, AgentState.TOOL_EXEC,
                        derived.tool() + " missing=" + derived.gap().missingSlots()
                                + " modelArgs=" + traceArgs(derived.slots()));
                return ModelRun.solo(askSlot(session, tenantId, customerId, conversationId, derived.gap(), query,
                        trace, sink, styleTier, usage));
            }
            if (derived != null && derived.missing().isEmpty() && !IdempotencyService.isWrite(derived.tool())) {
                // 读路径且槽位齐备：模型没发 function call 也要把这一枪开了，
                // 不能因为模型偷懒让用户拿不到自己订单的事实。写路径由 deriveActionCall 挡在门外。
                toolUsed = true;
                rounds++;
                int derivedAttempt = 1;
                LlmTypes.ToolCall derivedCall = new LlmTypes.ToolCall("gateway-derived",
                        derived.tool().apiName(), derived.slots());
                messages.add(LlmTypes.Message.assistant(null, List.of(derivedCall)));
                long derivedStarted = System.nanoTime();
                ToolDispatcher.Dispatch dispatch = dispatcher.dispatch(derivedCall, idempotencyToken,
                        dialogueText(query, session));
                long derivedMillis = (System.nanoTime() - derivedStarted) / 1_000_000L;
                step(trace, sink, AgentState.TOOL_EXEC,
                        "gateway-derived " + dispatch.tool() + "=" + dispatch.status()
                                + " modelArgs=" + traceArgs(derivedCall.arguments()));
                sink.toolExecuting(dispatch.tool(), dispatch.label());
                sink.toolResult(dispatch.tool(), dispatch.status(), summarize(dispatch));
                LlmTypes.ToolCall lastCall = derivedCall;
                if (dispatch.degraded()) {
                    // ③操作性失败（票 138 规则一/二）在这里的动作与主循环**同规则、不同形**：派生枪是网关
                    // 自己的确定性一枪（模型刚刚明确没发 function call），没有"下一轮模型重规划"可言，
                    // 所以反思 = 带上失败事实把同一读工具重试一次。占轮、按工具一次、写工具不反思三条相同；
                    // 重试仍走同一个 dispatch（槽位 / 溯源 / 幂等 / 行业门全套照过），撞闸就走闸的原出口。
                    if (!reflectionEligible(derived.tool(), rounds, reflectedTools)) {
                        noteReflectionExhausted(derived.tool(), reflectedTools);
                        return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink,
                                FallbackReason.TOOL_UNAVAILABLE, query, dispatch.json(), styleTier, usage));
                    }
                    reflectedTools.add(derived.tool());
                    noteReflection(derived.tool(), 2, trace, sink);
                    messages.add(LlmTypes.Message.tool(derivedCall.id(), dispatch.json()));
                    messages.add(LlmTypes.Message.user(REFLECTION_CONTEXT));
                    lastCall = new LlmTypes.ToolCall("gateway-derived-retry",
                            derived.tool().apiName(), derived.slots());
                    messages.add(LlmTypes.Message.assistant(null, List.of(lastCall)));
                    rounds++;
                    derivedAttempt = 2;
                    long retryStarted = System.nanoTime();
                    dispatch = dispatcher.dispatch(lastCall, idempotencyToken, dialogueText(query, session));
                    derivedMillis = (System.nanoTime() - retryStarted) / 1_000_000L;
                    step(trace, sink, AgentState.TOOL_EXEC,
                            "gateway-derived-retry " + dispatch.tool() + "=" + dispatch.status());
                    sink.toolExecuting(dispatch.tool(), dispatch.label());
                    sink.toolResult(dispatch.tool(), dispatch.status(), summarize(dispatch));
                    if (dispatch.degraded()) {
                        noteReflectionExhausted(derived.tool(), reflectedTools);
                        return ModelRun.solo(fallback(AgentState.TOOL_EXEC, trace, sink,
                                FallbackReason.TOOL_UNAVAILABLE, query, dispatch.json(), styleTier, usage));
                    }
                }
                // 票 48 的契约是「plan 记的是真的执行过的步」。网关派生这一枪真的派发了，所以也要进 plan——
                // 漏记会让 toolUsed=true 与 plan=[] 同现（票 65 的 task_done 读 plan，会因此误判）。
                planSteps.add(new AgentResult.PlanStep(derived.tool().apiName(),
                        dispatch.status() == null ? "UNKNOWN" : dispatch.status().name(),
                        derivedMillis, derived.slots(), derivedAttempt, derivedAttempt > 1));
                messages.add(LlmTypes.Message.tool(lastCall.id(), dispatch.json()));
            }
        }

        step(trace, sink, AgentState.REPLY, "streaming");
        String answer;
        if (lastReply != null && !lastReply.wantsTool() && lastReply.content() != null
                && (rounds == 0 || answeredByBudgetCheck)) {
            // 首轮就给出内容，或预算检查后模型明确不再要工具：直接把它按打字机切块推出去，不再多打一次模型。
            // token 字段照旧走总账（票 145 / R12 的第一处缺口：这里此前只报这一跳，4 轮用例因此上报 2,811 vs 计费 11,338）
            answer = lastReply.content();
            emitChunked(answer, sink);
        } else {
            try {
                LlmTypes.Reply finalReply = llm.stream(new LlmTypes.Request(messages, List.of(),
                        properties.llm().temperature()), sink::token);
                usage.add(finalReply);
                answer = finalReply.content();
            } catch (LlmException failure) {
                return ModelRun.solo(fallback(AgentState.REPLY, trace, sink, mapLlmFailure(failure), query,
                        failure.getMessage(), styleTier, usage));
            }
        }

        // 空答案不是答案（2026-09-28 黑盒 QA 实测）：模型既没发工具调用、也没给出正文时，
        // 今天会返回 HTTP 200 + `answer:""` + 无工单——买家什么也没拿到，也没有可查的出口。
        // 这与 ADR 0009「办不成就给一句实话并落可查工单」直接冲突，所以在这里兜住。
        // 复用 LLM_CIRCUIT_OPEN 的措辞（"智能回复暂时不可用，已为您登记人工处理"）：**不新增枚举值**，
        // 避免改动 README 的「枚举 10 / 降级 9」公开口径。
        if (answer == null || answer.isBlank()) {
            return ModelRun.solo(fallback(AgentState.REPLY, trace, sink, FallbackReason.LLM_CIRCUIT_OPEN, query,
                    "模型未产出正文且未派发工具（空答案）", styleTier, usage));
        }

        sessionStore.save(tenantId, customerId, sessionStore.appendTurn(clearPending(session), query, answer));

        List<String> citations = retrieved == null ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::ruleId).toList();
        List<String> citedScopes = retrieved == null ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::scope).toList();
        // 与 citations 同源同序的标题与出处：买家端引用详情要用它们（round33 / ADR 0067，出处 2026-10-17 补）。
        // 命中路径不跑检索，这两份必须随缓存条目一起落库，否则缓存命中时买家端又只剩"政策条款"。
        List<String> citedHeadings = retrieved == null ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::title).toList();
        List<String> citedDocs = retrieved == null ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::sourceDoc).toList();

        WriteBackPolicy.Verdict verdict = writeBackPolicy.evaluate(new WriteBackPolicy.Request(
                intent, answer, retrieved != null && !retrieved.empty(), toolUsed,
                retrieved != null && retrieved.degraded(), false));
        Optional<CacheEntry> written = Optional.empty();
        if (verdict.eligible()) {
            Optional<CacheEntry> prepared = cacheService.prepareWrite(tenantId, domain, intent, epoch, lookup, answer,
                    citedScopes, citations, citedHeadings, citedDocs, properties.llm().model());
            if (prepared.isEmpty()) {
                // 准入判定没给出向量（例如缓存被关），写回无从落点
                step(trace, sink, AgentState.CACHE_WRITE, "skipped:no-write-target");
            } else {
                // scope 进 trace：平台桶 vs 本店桶是坏缓存分区的唯一现场读数，
                // 排查"跨店同问同答"时不用去翻 Redis key（2026-10-17 修复时加）
                step(trace, sink, AgentState.CACHE_WRITE, "eligible scope=" + prepared.get().scope());
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
            // "空召回且未降级"的负标记写在上面那个确定性出口里（政策意图到不了这里）。
            // 这里只剩下检索降级的一格：检索引擎挂了不等于库里没有，写负缓存等于把一次抖动
            // 固化成 60 秒的批量转人工——2026-09-08 mix80 压测里就是这样把自己打停的。
            if (retrieved != null && retrieved.empty() && retrieved.degraded()) {
                negativeSuppressedCounter.increment();
                step(trace, sink, AgentState.CACHE_WRITE, "negative-suppressed:retrieval-degraded");
            }
        }

        return new ModelRun(new AgentResult(answer, intent, triage.layer(), CacheService.Layer.NONE, citations, trace,
                null, null, false, usage.prompt(), usage.completion(), toolUsed, false, promptCatalog.version(),
                List.copyOf(planSteps), contextComposition(retrieved, session, messages),
                citationDetailsOf(retrieved)), written);
    }

    /** 缺槽位：追问一次，仍缺则转人工。绝不猜（ADR 0008、ticket 11）。 */
    private AgentResult askSlot(SessionStore.Session session, String tenantId, String customerId,
                                String conversationId, ToolDispatcher.Dispatch dispatch, String query,
                                List<AgentResult.TraceStep> trace, EventSink sink, StyleService.Tier styleTier,
                                TokenUsage usage) {
        int asks = session.slotAskCount() + 1;
        if (asks > properties.agent().maxSlotAsks()) {
            return fallback(AgentState.SLOT_ASK, trace, sink, FallbackReason.SLOT_UNRESOLVED, query,
                    "已追问 " + session.slotAskCount() + " 次仍缺槽位", styleTier, usage);
        }
        String slot = dispatch.missingSlots().get(0);
        String question = dispatcher.question(dispatch.tool(), dispatch.missingSlots());
        step(trace, sink, AgentState.SLOT_ASK, dispatch.tool() + " 缺 " + slot);
        sink.slotAsk(slot, question);
        sessionStore.save(tenantId, customerId, new SessionStore.Session(conversationId, session.turns(),
                dispatch.tool().apiName(), new LinkedHashMap<>(Map.of("askedSlot", slot)), asks));
        return new AgentResult(question, dispatch.tool().intent(), "SLOT", CacheService.Layer.NONE, List.of(), trace,
                null, null, true, usage.prompt(), usage.completion(), false, false, promptCatalog.version(), List.of(),
                AgentResult.ContextComposition.NONE, List.of());
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
                                      StyleService.Tier styleTier, TokenUsage usage) {
        step(trace, sink, AgentState.SLOT_ASK, "合并补充信息");
        ToolName tool = ToolName.fromApiName(session.pendingTool());
        if (tool == null) {
            sessionStore.save(tenantId, customerId, clearPending(session));
            return fallback(AgentState.SLOT_ASK, trace, sink, FallbackReason.INTENT_UNRESOLVED, query,
                    "待办工具已失效", styleTier, usage);
        }
        Map<String, Object> arguments = extractSlots(tool, query);
        List<String> missing = dispatcher.missingSlots(tool, arguments);
        if (!missing.isEmpty()) {
            String slot = missing.get(0);
            String question = dispatcher.question(tool, missing);
            int asks = session.slotAskCount() + 1;
            if (asks > properties.agent().maxSlotAsks()) {
                return fallback(AgentState.SLOT_ASK, trace, sink, FallbackReason.SLOT_UNRESOLVED, query, null,
                        styleTier, usage);
            }
            sink.slotAsk(slot, question);
            sessionStore.save(tenantId, customerId, new SessionStore.Session(conversationId, session.turns(),
                    tool.apiName(), arguments, asks));
            return new AgentResult(question, tool.intent(), "SESSION", CacheService.Layer.NONE, List.of(), trace,
                    null, null, true, usage.prompt(), usage.completion(), false, false, promptCatalog.version(),
                    List.of(), AgentResult.ContextComposition.NONE, List.of());
        }
        LlmTypes.ToolCall call = new LlmTypes.ToolCall("resumed", tool.apiName(), arguments);
        ToolDispatcher.Dispatch dispatch = dispatcher.dispatch(call, idempotencyToken, dialogueText(query, session),
                requestFingerprint(query, idempotencyToken));
        step(trace, sink, AgentState.TOOL_EXEC, tool + "=" + dispatch.status());
        if (dispatch.duplicate()) {
            sink.duplicateSubmit(tool, "该请求已处理过，本次未重复执行");
        } else {
            sink.toolExecuting(tool, dispatch.label());
        }
        sink.toolResult(tool, dispatch.status(), summarize(dispatch));
        if (dispatch.status() == ToolStatus.PENDING_APPROVAL) {
            return pendingApprovalAnswer(dispatch, session, query, sink, trace, tenantId, customerId, "SESSION",
                    List.of(), usage);
        }
        sessionStore.save(tenantId, customerId, clearPending(session));
        // ③操作性失败（票 138 规则一/二）：续办枪与派生枪同形——工具与槽位都是确定性得来的，没有"下一轮
        // 模型重规划"可言，所以反思 = 同一读工具带失败事实重试一次（占轮、按工具一次、写工具不反思）。
        // 这条路径只可能是读工具（写工具的待办在 deriveActionCall 与 askSlot 那两处就被挡下），
        // 但读工具的判断仍走同一个 reflectionEligible，不在这里另开一套。
        String firstFailure = null;
        if (dispatch.degraded()) {
            Set<ToolName> reflectedTools = new HashSet<>();
            if (!reflectionEligible(tool, 1, reflectedTools)) {
                noteReflectionExhausted(tool, reflectedTools);
                return fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE, query,
                        dispatch.json(), styleTier, usage);
            }
            reflectedTools.add(tool);
            firstFailure = dispatch.json();
            noteReflection(tool, 2, trace, sink);
            dispatch = dispatcher.dispatch(call, idempotencyToken, dialogueText(query, session),
                    requestFingerprint(query, idempotencyToken));
            step(trace, sink, AgentState.TOOL_EXEC, tool + "=retry " + dispatch.status());
            sink.toolExecuting(tool, dispatch.label());
            sink.toolResult(tool, dispatch.status(), summarize(dispatch));
            if (dispatch.degraded()) {
                noteReflectionExhausted(tool, reflectedTools);
                return fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE, query,
                        dispatch.json(), styleTier, usage);
            }
        }
        List<LlmTypes.Message> messages = new ArrayList<>();
        messages.add(LlmTypes.Message.system(systemPrompt));
        // 反思过的那一枪把两次事实都摆给模型：失败枪不是业务事实，重试结果才是（票 144 规则二）。
        String businessResult = firstFailure == null ? dispatch.json()
                : "【第一次调用】\n" + firstFailure + "\n【重试后】\n" + dispatch.json();
        messages.add(LlmTypes.Message.user(query + "\n\n【业务系统返回】\n" + businessResult));
        try {
            LlmTypes.Reply reply = llm.stream(new LlmTypes.Request(messages, List.of(),
                    properties.llm().temperature()), sink::token);
            usage.add(reply);
            sessionStore.save(tenantId, customerId,
                    sessionStore.appendTurn(clearPending(session), query, reply.content()));
            return new AgentResult(reply.content(), tool.intent(), "SESSION", CacheService.Layer.NONE, List.of(),
                    trace, null, null, false, usage.prompt(), usage.completion(), true, false,
                    promptCatalog.version(), List.of(), AgentResult.ContextComposition.NONE, List.of());
        } catch (LlmException failure) {
            return fallback(AgentState.REPLY, trace, sink, mapLlmFailure(failure), query, failure.getMessage(),
                    styleTier, usage);
        }
    }

    private AgentResult fallback(AgentState from, List<AgentResult.TraceStep> trace, EventSink sink,
                                 FallbackReason reason, String query, String detail, StyleService.Tier styleTier,
                                 TokenUsage usage) {
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
        // 降级出口也回填已经花掉的轮次（票 145 / R12 的第二处缺口）：此前恒报 0，
        // 而"办了 4 轮最后降级"的请求其实已经付过钱了。
        return new AgentResult(answer, Intent.ESCALATE, "FALLBACK", CacheService.Layer.NONE, List.of(), trace,
                reason, ticket.orElse(null), false, usage.prompt(), usage.completion(), false, true,
                promptCatalog.version(), List.of(), AgentResult.ContextComposition.NONE, List.of());
    }

    /**
     * 按意图决定下发哪些工具（ADR 0007：意图枚举是唯一驱动源）。
     *
     * <p>政策咨询与转人工一律不下发工具：实测给了工具，小模型会凭空编一个订单号去查，
     * 把一句政策咨询答成"查不到您的订单"。落到任一动作意图时下发全部四个业务工具，让工具选择本身充当
     * 意图证据——dev 评测（DashScope qwen-plus，180 条）实测按子意图裁到单个工具，会把 T0 的路由误差
     * 放大成选错工具：判成 ACTION_ORDER 的改地址请求只拿到 queryOrderDetail，模型没有选对的机会。
     *
     * <p>round33 / ADR 0067 加行业门：**电商工具只属于电商行业**。酒店租户的"我要取消订单"
     * 即使被判成动作意图也拿不到 APPLY_REFUND，只能照实说明办不了并转人；未登记行业的租户
     * 同样 fail-closed。少了这道门，"酒店不碰电商工具"就只是 T0 词表的好运气——T1 质心层
     * 一旦把句子判成 ACTION_REFUND，全套电商工具又会挂回去。
     */
    private List<Map<String, Object>> toolsFor(TriageResult triage, Domain domain) {
        if (domain != Domain.ECOMMERCE) {
            return List.of();
        }
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
    private boolean reflectionEligible(ToolName tool, int rounds, Set<ToolName> reflectedTools) {
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
    private void noteReflection(ToolName tool, int attempt, List<AgentResult.TraceStep> trace, EventSink sink) {
        reflectionTriggeredCounter.increment();
        step(trace, sink, AgentState.PLAN, "reflection=" + tool.apiName() + " attempt=" + attempt);
    }

    /**
     * 配额用尽那一格（票 139 的三态判据：同工具第二次③失败）：counter 的 {@code exhausted} +1。
     *
     * <p>写工具与"没有轮预算"两种情况**都不计数**——前者不在反思范围内（票 138 规则四），
     * 后者是预算边界、不是配额用尽，两格都不该被它们灌进来。
     */
    private void noteReflectionExhausted(ToolName tool, Set<ToolName> reflectedTools) {
        if (tool != null && !IdempotencyService.isWrite(tool) && reflectedTools.contains(tool)) {
            reflectionExhaustedCounter.increment();
        }
    }

    /** 包级可见只为让降级映射进用例（ticket 14）；三类模型失败必须各自映射到不同 reason。 */
    static FallbackReason mapLlmFailure(LlmException failure) {
        return switch (failure.kind()) {
            case TIMEOUT -> FallbackReason.LLM_TIMEOUT;
            case UNAVAILABLE -> FallbackReason.LLM_CIRCUIT_OPEN;
            case BUDGET_EXCEEDED -> FallbackReason.LLM_BUDGET_EXCEEDED;
        };
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
    private static AgentResult.ContextComposition contextComposition(
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
     * 引用详情（round33 / ADR 0067）：把检索结果里的标题与出处搬进可输出的字段。
     *
     * <p>与 {@code citations} 同源同序——买家端引用列表的两列就是这两份数据的拼装。
     * 零召回时是空列表，不是 null。
     */
    private static List<AgentResult.CitationDetail> citationDetailsOf(HybridRetriever.Result retrieved) {
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
     * 命中路径的引用详情：标题与出处都随缓存条目落库（{@link CacheEntry}），
     * 与 {@code citations} 同源同序。
     *
     * <p>{@code sourceDocs} 是 2026-10-17 补的：round33 落了标题却漏了出处，于是命中路径
     * {@code citationDetails.doc} 恒为空串——买家端引用列表的两列本就是从这两份数据拼的。
     */
    private static List<AgentResult.CitationDetail> citationDetailsOf(List<String> ruleIds, List<String> headings,
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
     * 粗估 token：中日韩字符按 1 token/字、其余按 4 字符/token。
     *
     * <p>这是业界常用启发式，**不是真分词**。它只回答"这次注入了多大一坨"，够用；要精确值就得引
     * 分词器，那与本轮「旁挂观测」的定义不符，也不该为一个观测字段增加依赖面。
     */
    private static int estimateTokens(String text) {
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

    /**
     * 订单号溯源守卫的判据面（ADR 0045 票 56）：**买家说过的话**——本轮 query 加历史里的买家轮次。
     *
     * <p>刻意只收买家轮次，不收助手回复、也不收检索回来的政策条款：助手回复可能转述过模型编的
     * 单号，条款正文里也可能出现数字——把它们算进来，等于用自己编的东西给自己背书，第二道守卫
     * 就白设了。续办路径天然覆盖：{@code resumePending} 的参数由 {@code extractSlots(tool, query)}
     * 从**本轮补充的那句话**里取，而那句话就是这里的 query。
     */
    /**
     * 请求指纹：本轮 query 归一化后的哈希（ADR 0046 票 58）。
     *
     * <p>只在客户端**显式**带了 token 时才算，否则返回 null。两条理由：派生 token 在模型之前算不出来
     * （它要工具参数），而"客户端复用旧 token 却换了要求"这个问题也只在显式 token 下存在。传 null 时
     * 幂等层既不写、也不查请求级索引 —— 行为与 ADR 0008 时期逐字一致。
     */
    private static String requestFingerprint(String query, String idempotencyToken) {
        if (idempotencyToken == null || idempotencyToken.isBlank()) {
            return null;
        }
        return QueryNormalizer.md5(QueryNormalizer.normalize(query));
    }

    /**
     * 幂等回放的回答出口：确定性话术 + {@code duplicate_submit} 事件，不打模型、不落缓存、不进工具循环。
     *
     * <p>会话照常推进（回放也是这一轮真实发生过的事，下一轮该看得到它），并清掉待办动作——若走到这里，
     * 说明那次请求已经办成过，会话上不该还挂着一个待补的槽位。
     */
    private AgentResult replayAnswer(IdempotencyService.Replay replay, SessionStore.Session session, String query,
                                     EventSink sink, List<AgentResult.TraceStep> trace, String tenantId,
                                     String customerId, String conversationId, TokenUsage usage) {
        Intent intent = replay.tool().intent();
        String answer = ReplayReply.render(replay.tool(), replay.resultJson());
        sink.meta(conversationId, intent, CacheService.Layer.NONE);
        step(trace, sink, AgentState.REPLY, "replay=" + replay.tool().apiName());
        sink.duplicateSubmit(replay.tool(), answer);
        sessionStore.save(tenantId, customerId, sessionStore.appendTurn(clearPending(session), query, answer));
        sink.token(answer);
        // 回放不打模型，但入口层那一跳照付（票 145 / R12）：照实回填，不假报 0。
        return new AgentResult(answer, intent, "replay", CacheService.Layer.NONE, List.of(), trace, null, null,
                false, usage.prompt(), usage.completion(), true, false, promptCatalog.version(), List.of(),
                AgentResult.ContextComposition.NONE, List.of());
    }

    /**
     * 退款审批闸门的受理出口（ADR 0047 票 59）：确定性受理话术 + {@code shoppilot_refund_pending_total}，
     * 不打模型、不落缓存、不进工具循环。会话照常推进并清掉待办动作——受理已经发生过。
     *
     * <p>与 {@link #replayAnswer} 的区别是事件：受理推的是 {@code tool_result}（已在调用点发出），
     * 不是 {@code duplicate_submit} —— 这是**新受理的一笔**，不是重复提交。
     */
    private AgentResult pendingApprovalAnswer(ToolDispatcher.Dispatch dispatch, SessionStore.Session session,
                                              String query, EventSink sink, List<AgentResult.TraceStep> trace,
                                              String tenantId, String customerId, String layer,
                                              List<AgentResult.PlanStep> planSteps, TokenUsage usage) {
        registry.counter("shoppilot_refund_pending_total").increment();
        String answer = ReplayReply.pendingApproval(dispatch.tool(), dispatch.json());
        step(trace, sink, AgentState.REPLY, "pending-approval=" + dispatch.tool().apiName());
        sessionStore.save(tenantId, customerId, sessionStore.appendTurn(clearPending(session), query, answer));
        sink.token(answer);
        // 受理即收尾，但受理之前那次规划调用已经花掉了（票 145 / R12 的第二处缺口）。
        return new AgentResult(answer, dispatch.tool().intent(), layer, CacheService.Layer.NONE, List.of(), trace,
                null, null, false, usage.prompt(), usage.completion(), true, false, promptCatalog.version(),
                planSteps, AgentResult.ContextComposition.NONE, List.of());
    }

    private static String dialogueText(String query, SessionStore.Session session) {
        StringBuilder text = new StringBuilder(query == null ? "" : query);
        if (session != null && session.turns() != null) {
            for (SessionStore.Turn turn : session.turns()) {
                if ("user".equals(turn.role())) {
                    text.append('\n').append(turn.text() == null ? "" : turn.text());
                }
            }
        }
        return text.toString();
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
