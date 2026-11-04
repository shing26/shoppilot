package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheEntry;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.cache.QueryNormalizer;
import com.shoppilot.gateway.cache.SingleFlight;
import com.shoppilot.gateway.channel.ChannelContext;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.orchestration.AgentRole;
import com.shoppilot.gateway.orchestration.HandoffBlockReason;
import com.shoppilot.gateway.orchestration.HandoffGraph;
import com.shoppilot.gateway.orchestration.HandoffStep;
import com.shoppilot.gateway.orchestration.HandoffTrigger;
import com.shoppilot.gateway.orchestration.RouteContext;
import com.shoppilot.gateway.orchestration.ToolSurfaceDecision;
import com.shoppilot.gateway.orchestration.ToolSurfacePolicy;
import com.shoppilot.gateway.sentiment.SentimentGate;
import com.shoppilot.gateway.style.StyleService;
import com.shoppilot.gateway.triage.TriageEngine;
import com.shoppilot.gateway.triage.TriageResult;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolName;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 一轮请求的编排者（round48 票 203）。
 *
 * <p>把入口面（INTAKE / TRIAGE / 幂等回放 / 续办 / Handoff / 缓存与穿透合并 / 复合任务）留在本类，
 * 模型面拆给四个 handler 串成固定管线：{@code Retrieve → PlanExecute → Compose → Persist}。
 * 判定次序、SSE 事件顺序、trace 文本与指标名逐行搬自原 {@code AgentStateMachine}——本类只换 owner。
 */
final class TurnOrchestrator {

    private final TurnServices services;
    private final RetrieveHandler retrieve;
    private final ComposeHandler compose;
    private final SlotSupport slots;
    private final PlanExecuteHandler plan;
    private final PersistHandler persist;

    TurnOrchestrator(TurnServices services) {
        this.services = services;
        this.compose = new ComposeHandler(services);
        this.slots = new SlotSupport(services, compose);
        this.plan = new PlanExecuteHandler(services, compose, slots);
        this.retrieve = new RetrieveHandler(services);
        this.persist = new PersistHandler(services);
    }

    AgentResult run(String query, String idempotencyToken, EventSink sink) {
        // ADR 0072：路由事实只属于本次请求；同步与流式工作线程都必须显式清理。
        RouteContext.clear();
        try {
            return runRouted(query, idempotencyToken, sink);
        } finally {
            RouteContext.clear();
        }
    }

    private AgentResult runRouted(String query, String idempotencyToken, EventSink sink) {
        List<AgentResult.TraceStep> trace = new ArrayList<>();
        String tenantId = TenantContext.tenantId();
        String customerId = TenantContext.customerId();
        String conversationId = TenantContext.current().conversationId();
        TurnServices.step(trace, sink, AgentState.INTAKE, "tenant=" + tenantId);

        // 会话归属是「店铺 + 买家」两者（ADR 0025）：只按店铺载会话，同店铺里换个人填同一个会话 id，
        // 载出来的就是别人的对话轮次与别人没办完的待办动作。
        SessionStore.Session session = services.sessionStore.load(tenantId, customerId, conversationId);
        long epoch = services.kbEpoch.current();
        // 行业是**显式登记**出来的，不是从店铺名或问句猜的（ADR 0067）。未登记租户 fail-closed 成
        // UNKNOWN：检索只看本租户规则，缓存平台桶按 UNKNOWN 分区。这一格同时喂给意图判定、检索、
        // 缓存与工具裁剪四处，是 round33 修跨行业串答案的唯一来源。
        Domain domain = services.domainRegistry.resolve(tenantId);

        // 情绪门（ADR 0034）：INTAKE → TRIAGE 之间，词典层 0 token。高情绪在这里直接落工单转人工，
        // 不让激动的买家走完检索/模型/工具全链路才被转接——等待本身就是二次激怒。
        // 状态机 10 状态不扩：情绪判定发生在 INTAKE 状态内部；perf 口径下第二层 LLM 分类不启用。
        SentimentGate.Verdict sentiment = services.sentimentGate.evaluate(query);
        TurnServices.step(trace, sink, AgentState.INTAKE,
                "sentiment=" + sentiment.emotion() + " via " + sentiment.source());
        // 本请求的 LLM 用量账本（票 145 / R12）：入口层这一跳先入账——它连缓存命中也要付钱，
        // 而它在缓存查询之前，所以必须在任何出口之前就开好这本账。
        TokenUsage usage = new TokenUsage(sentiment.promptTokens(), sentiment.completionTokens());
        // 风格档位（ADR 0038）：channel × emotion × intent → 档位，注入段拼在版本化基座之后；
        // 回答正文仍由同一次 LLM 调用产出。档位随参数穿透到各降级出口，话术选择随档位联动。
        StyleService.Tier styleTier = services.styleService.tierFor(ChannelContext.current(), sentiment.emotion(), null);
        services.registry.counter("shoppilot_style_applied_total", "style", styleTier.name()).increment();
        // 档位同时进 trace（与 sentiment= 同构）：离线评测与验收脚本按同步响应就能归因提示词形态，
        // 不必为它新增响应字段（票 35 登记的"离线归因"触发条件由此关闭）
        TurnServices.step(trace, sink, AgentState.INTAKE, "style=" + styleTier.name());
        sink.style(styleTier.name());
        // 店铺人格（round37）：按已验签租户追加一段「店名/主营/本店承诺」，再叠风格档位。
        // 未登记租户不追加，避免拿别家店的资料补位。
        String systemPrompt = compose.systemPrompt(tenantId, styleTier);
        // 显式转人工优先于情绪判定（ADR 0042）：ADR 0017 把「转人工」下沉到 T0 就是为了不依赖任何
        // 服务，而情绪门位于 INTAKE、先于意图判定，会把这条平静问句判成 URGENT 后抢先转接，于是 T0
        // 那张含「转人工」的词表根本没机会执行（2026-09-20 矩阵 F2；2026-09-23 活体验收 step 7 复现）。
        // 命中显式升级词表时不走情绪短路，放行到 triage，由既有 USER_REQUESTED 出口收口。
        if (sentiment.escalated() && !services.triageEngine.isExplicitEscalation(query)) {
            sink.meta(conversationId, null, CacheService.Layer.NONE);
            return services.fallback(AgentState.INTAKE, trace, sink, FallbackReason.EMOTION_ESCALATION, query,
                    "emotion=" + sentiment.emotion() + " via " + sentiment.source(), styleTier, usage);
        }

        String requestFingerprint = SlotSupport.requestFingerprint(query, idempotencyToken);

        // 请求级幂等回放（ADR 0046 票 58）：客户端带着同一个 token 重试**同一次请求**时，回放首次结果，
        // 不打模型、也不重复执行。放这一位置有两条理由：① 不扰动上面那道情绪门的既有优先级（ADR 0034/0042
        // 的排序是两次回归换来的，回放不该抢在安全出口之前）；② 放在 hasPending 之前——回放不是槽位补齐。
        // 它刻意**不**走 fallback 出口：回放不是降级，落工单会污染「降级 9」的口径（ADR 0009/0046）。
        if (requestFingerprint != null) {
            Optional<IdempotencyService.Replay> replay =
                    services.dispatcher.lookupReplay(idempotencyToken.trim(), requestFingerprint);
            if (replay.isPresent()) {
                return compose.replayAnswer(replay.get(), session, query, sink, trace, tenantId, customerId,
                        conversationId, usage);
            }
        }

        if (session.hasPending()) {
            sink.meta(conversationId, null, CacheService.Layer.NONE);
            return slots.resumePending(session, query, idempotencyToken, tenantId, customerId, conversationId,
                    trace, sink, systemPrompt, styleTier, usage);
        }

        TurnServices.step(trace, sink, AgentState.TRIAGE, "开始判定");
        TriageEngine.Outcome outcome = services.triageEngine.triage(query, domain, tenantId);
        TriageResult triage = outcome.result();
        TurnServices.step(trace, sink, AgentState.TRIAGE,
                "layer=" + triage.layer() + " intent=" + triage.intent() + " admissible=" + triage.cacheAdmissible());
        // ADR 0072 H1：Router 仍是确定性纯函数；只有“上一轮售前 + 本轮需要售后工具”才算同诉求升级。
        // 普通换话题按 route reset 处理，不落工单、不计 blocked（H2 再接 blocked 出口）。
        AgentRole requested = AgentRole.routeFor(triage.intent());
        // blocked trace / metric 必须用**升级前**的角色；session 角色在后面才推进到请求角色。
        AgentRole previousRole = session.currentRole();
        boolean actionEvidence = triage.entityFound() || (triage.intent() != null && triage.intent().isAction());
        long handoffDecisionStarted = System.nanoTime();
        HandoffTrigger.Decision routing = services.handoffTrigger.evaluate(previousRole, requested, actionEvidence,
                session.handoffTrail(), System.currentTimeMillis());
        long handoffDecisionNanos = System.nanoTime() - handoffDecisionStarted;
        services.handoffDecisionTimer.record(handoffDecisionNanos, TimeUnit.NANOSECONDS);
        services.handoffDecisionMaxNanos.accumulateAndGet(handoffDecisionNanos, Math::max);
        AgentRole route = routing.effectiveRole();
        List<HandoffStep> trail = new ArrayList<>(session.handoffTrail());
        if (routing.kind() == HandoffTrigger.Kind.ALLOWED) {
            routing.step().ifPresent(trail::add);
            services.registry.counter("shoppilot_handoff_total",
                    "from", routing.step().orElseThrow().from(),
                    "to", routing.step().orElseThrow().to(),
                    "result", "ALLOWED").increment();
            TurnServices.step(trace, sink, AgentState.TRIAGE, "HANDOFF=" + routing.step().orElseThrow().from()
                    + "->" + routing.step().orElseThrow().to()
                    + " depth=" + routing.step().orElseThrow().depth() + " result=ALLOWED");
        }
        services.registry.counter("shoppilot_agent_role_total", "role", route.name()).increment();
        ToolSurfaceDecision surface = ToolSurfacePolicy.decide(route, domain, triage.intent());
        TurnServices.step(trace, sink, AgentState.TRIAGE,
                "route=" + route.name() + " toolSurface=" + route.toolSurface() + " surfaceReason=" + surface.reason());
        SessionStore.Session routedSession = session.withRouting(route, trail);
        if (!routedSession.equals(session)) {
            services.sessionStore.save(tenantId, customerId, routedSession);
        }
        session = routedSession;
        final SessionStore.Session routed = session;

        // ADR 0072 H2：blocked 只属于 active handoff；普通换话题在 trigger 里已是 route reset。
        // 本轮不再进模型/工具推理，落可查工单；session 角色推进到请求角色，避免同一诉求反复打 blocked 单。
        if (routing.kind() == HandoffTrigger.Kind.BLOCKED) {
            HandoffBlockReason blockedReason = routing.blockedReason().orElse(HandoffBlockReason.NO_EDGE);
            services.registry.counter("shoppilot_handoff_total",
                    "from", previousRole == null ? "NONE" : previousRole.name(),
                    "to", requested.name(),
                    "result", "BLOCKED").increment();
            services.registry.counter("shoppilot_handoff_blocked_total",
                    "from", previousRole == null ? "NONE" : previousRole.name(),
                    "to", requested.name(),
                    "reason", blockedReason.name()).increment();
            TurnServices.step(trace, sink, AgentState.TRIAGE, "HANDOFF_BLOCKED="
                    + (previousRole == null ? "NONE" : previousRole.name())
                    + "->" + requested.name() + " reason=" + blockedReason.name());
            RouteContext.set(new RouteContext.Snapshot(requested, trail,
                    SlotSupport.confirmedSlots(triage.intent(), query),
                    triage.intent() == null ? null : triage.intent().name(), blockedReason,
                    services.promptCatalog.version(), HandoffGraph.POLICY_VERSION));
            sink.meta(conversationId, Intent.ESCALATE, CacheService.Layer.NONE);
            return services.fallback(AgentState.TRIAGE, trace, sink, FallbackReason.INTENT_UNRESOLVED, query,
                    "handoff blocked reason=" + blockedReason.name(), styleTier, usage);
        }

        RouteContext.set(new RouteContext.Snapshot(route, trail, SlotSupport.confirmedSlots(triage.intent(), query),
                triage.intent() == null ? null : triage.intent().name(), null,
                services.promptCatalog.version(), HandoffGraph.POLICY_VERSION));

        if (triage.intent() == Intent.ESCALATE) {
            sink.meta(conversationId, Intent.ESCALATE, CacheService.Layer.NONE);
            // R7：门店细则判定的转人（policyHuman）用 INTENT_UNRESOLVED——系统无法自动判定，
            // 与买家主动喊人工（USER_REQUESTED）区分；两者都落可查工单。
            FallbackReason reason = triage.policyHuman()
                    ? FallbackReason.INTENT_UNRESOLVED
                    : FallbackReason.USER_REQUESTED;
            return services.fallback(AgentState.TRIAGE, trace, sink, reason, query, null, styleTier, usage);
        }

        // ADR 0077 路线 A：只对一个确定性复合场景（订单详情 + 物流）走两步工具，
        // 不把通用链路改成自主循环；未命中条件的请求仍走原状态机。
        if (isCompoundOrderQuery(triage.intent(), query)) {
            sink.meta(conversationId, triage.intent(), CacheService.Layer.NONE);
            return runCompoundOrderRead(new TurnContext(query, idempotencyToken, tenantId, customerId,
                    conversationId, routed, epoch, domain, triage, route, trace, sink, systemPrompt, styleTier, usage,
                    CacheService.Lookup.disabled()));
        }

        if (triage.cacheAdmissible()) {
            TurnServices.step(trace, sink, AgentState.CACHE_READ, "intent=" + triage.intent());
            CacheService.Lookup lookup = services.cacheService.lookup(tenantId, domain, triage.intent(), query, epoch,
                    outcome.queryVector());
            if (lookup.hit()) {
                sink.meta(conversationId, triage.intent(), lookup.layer());
                TurnServices.step(trace, sink, AgentState.REPLY, "cache=" + lookup.layer());
                CacheEntry entry = lookup.entry().get();
                sink.token(entry.answer());
                // 票 49 的不变量「context.ruleIds 与 citations 同源同序」在命中路径同样成立：
                // 缓存条目随带引用（ADR 0003），这里把它镜像进观测字段。另外两格照实为零——
                // 命中路径没有 Prompt，所以没有历史轮、也没有估出来的 token 数。
                // 但 token 字段**不是零**（票 145 / R12 的第三处缺口）：这一跳入口层的情绪分类已经付过钱，
                // 账本里就记着它。
                return new AgentResult(entry.answer(), triage.intent(), triage.layer(), lookup.layer(),
                        entry.sourceRuleIds(), trace, null, null, false, usage.prompt(), usage.completion(),
                        false, false, services.promptCatalog.version(), List.of(),
                        new AgentResult.ContextComposition(List.copyOf(entry.sourceRuleIds()), 0, 0),
                        ComposeHandler.citationDetailsOf(entry.sourceRuleIds(), entry.sourceHeadings(),
                                entry.sourceDocs()));
            }
            if (lookup.negative()) {
                sink.meta(conversationId, triage.intent(), CacheService.Layer.NONE);
                TurnServices.step(trace, sink, AgentState.CACHE_READ, "negative-marker");
                return services.fallback(AgentState.CACHE_READ, trace, sink, FallbackReason.INTENT_UNRESOLVED,
                        query, "该问题此前已确认无对应政策条款", styleTier, usage);
            }
            // 穿透合并：同一 key 只放一个请求进模型（ADR 0006）
            String flightKey = "shoppilot:c:flight:" + QueryNormalizer.md5(
                    tenantId + "|" + triage.intent().name() + "|" + lookup.normalizedQuery());
            SingleFlight.Gate gate = services.singleFlight.join(flightKey);
            if (!gate.leader()) {
                sink.meta(conversationId, triage.intent(), CacheService.Layer.FLIGHT);
                TurnServices.step(trace, sink, AgentState.CACHE_READ, "singleflight-reuse");
                gate.shared().ifPresent(entry -> sink.token(entry.answer()));
                return gate.shared()
                        .map(entry -> new AgentResult(entry.answer(), triage.intent(), triage.layer(),
                                CacheService.Layer.FLIGHT, entry.sourceRuleIds(), trace, null, null, false,
                                usage.prompt(), usage.completion(), false, false,
                                services.promptCatalog.version(), List.of(),
                                AgentResult.ContextComposition.NONE,
                                ComposeHandler.citationDetailsOf(entry.sourceRuleIds(), entry.sourceHeadings(),
                                        entry.sourceDocs())))
                        .orElseGet(() -> services.fallback(AgentState.CACHE_READ, trace, sink,
                                FallbackReason.INTENT_UNRESOLVED, query, null, styleTier, usage));
            }
            boolean published = false;
            try {
                sink.meta(conversationId, triage.intent(), CacheService.Layer.NONE);
                ModelRun run = runModelPath(new TurnContext(query, idempotencyToken, tenantId, customerId,
                        conversationId, routed, epoch, domain, triage, route, trace, sink, systemPrompt, styleTier,
                        usage, lookup));
                // 只有真的写回缓存的那条答案才广播给等待者，降级话术一律不共享（ADR 0006）
                published = run.shareable();
                services.singleFlight.publish(flightKey, run.cacheEntry());
                return run.result();
            } finally {
                if (!published) {
                    services.singleFlight.abandon(flightKey);
                }
            }
        }

        sink.meta(conversationId, triage.intent(), CacheService.Layer.NONE);
        return runModelPath(new TurnContext(query, idempotencyToken, tenantId, customerId, conversationId, routed,
                epoch, domain, triage, route, trace, sink, systemPrompt, styleTier, usage,
                CacheService.Lookup.disabled())).result();
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

    /** 执行前的可读计划：按意图给出稳定步骤，不让模型自由生成计划。 */
    private static List<String> plannedStepsFor(Intent intent) {
        if (intent == null) {
            return List.of("梳理问题", "生成回答");
        }
        return switch (intent) {
            case ACTION_LOGISTICS -> List.of("核对订单", "查询实时物流", "汇总结果");
            case ACTION_ORDER -> List.of("查询订单详情", "核对订单状态", "给出结果");
            case ACTION_ADDRESS -> List.of("确认订单是否可改址", "修改收货地址", "回报修改结果");
            case ACTION_REFUND -> List.of("确认订单是否可退款", "发起退款申请", "回报受理状态");
            default -> List.of("检索店铺政策", "整理依据", "生成回答");
        };
    }

    /** ADR 0077：确定性复合任务的唯一入口。 */
    private AgentResult runCompoundOrderRead(TurnContext ctx) {
        TriageResult triage = ctx.triage();
        String query = ctx.query();
        String idempotencyToken = ctx.idempotencyToken();
        List<AgentResult.TraceStep> trace = ctx.trace();
        EventSink sink = ctx.sink();
        TokenUsage usage = ctx.usage();
        List<String> planned = List.of("查询订单详情", "查询实时物流", "汇总订单与物流结果");
        sink.plan(planned);
        TurnServices.step(trace, sink, AgentState.PLAN, "compound=order+logistics");
        long detailStarted = System.nanoTime();
        ToolDispatcher.Dispatch detail = compoundDispatch(ToolName.QUERY_ORDER_DETAIL, ctx);
        if (detail.needsSlot() || detail.fabricated()) {
            return slots.askSlot(ctx, detail);
        }
        if (detail.degraded()) {
            return services.fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE, query,
                    detail.json(), ctx.styleTier(), usage);
        }
        long logisticsStarted = System.nanoTime();
        ToolDispatcher.Dispatch logistics = compoundDispatch(ToolName.QUERY_LOGISTICS, ctx);
        if (logistics.needsSlot() || logistics.fabricated()) {
            return slots.askSlot(ctx, logistics);
        }
        if (logistics.degraded()) {
            return services.fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE, query,
                    logistics.json(), ctx.styleTier(), usage);
        }

        String answer = compoundOrderAnswer(detail, logistics);
        sink.token(answer);
        persist.completeTurn(ctx, answer);
        services.registry.counter("shoppilot_compound_tasks_total", "result", "COMPLETED").increment();
        List<AgentResult.PlanStep> planSteps = List.of(
                new AgentResult.PlanStep(ToolName.QUERY_ORDER_DETAIL.apiName(), detail.status().name(),
                        (System.nanoTime() - detailStarted) / 1_000_000,
                        SlotSupport.extractSlots(ToolName.QUERY_ORDER_DETAIL, query), 1, false),
                new AgentResult.PlanStep(ToolName.QUERY_LOGISTICS.apiName(), logistics.status().name(),
                        (System.nanoTime() - logisticsStarted) / 1_000_000,
                        SlotSupport.extractSlots(ToolName.QUERY_LOGISTICS, query), 1, false));
        return new AgentResult(answer, triage.intent(), triage.layer(), CacheService.Layer.NONE, List.of(),
                trace, null, null, false, usage.prompt(), usage.completion(), true, false,
                services.promptCatalog.version(), planSteps, AgentResult.ContextComposition.NONE, List.of());
    }

    private ToolDispatcher.Dispatch compoundDispatch(ToolName tool, TurnContext ctx) {
        LlmTypes.ToolCall call = new LlmTypes.ToolCall("compound-" + tool.apiName(), tool.apiName(),
                SlotSupport.extractSlots(tool, ctx.query()));
        ToolDispatcher.Dispatch dispatch = services.dispatcher.dispatch(call, ctx.idempotencyToken(),
                SlotSupport.dialogueText(ctx.query(), ctx.session()),
                SlotSupport.requestFingerprint(ctx.query(), ctx.idempotencyToken()));
        TurnServices.step(ctx.trace(), ctx.sink(), AgentState.TOOL_EXEC, tool + "=" + dispatch.status());
        if (!dispatch.needsSlot() && !dispatch.fabricated()) {
            ctx.sink().toolExecuting(tool, dispatch.label());
            ctx.sink().toolResult(tool, dispatch.status(), TurnServices.summarize(dispatch));
        }
        return dispatch;
    }

    private static boolean isCompoundOrderQuery(Intent intent, String query) {
        if (intent != Intent.ACTION_LOGISTICS || query == null || query.isBlank()) {
            return false;
        }
        boolean asksLogistics = query.contains("物流") || query.contains("快递");
        boolean asksDetail = query.contains("详情") || query.contains("状态");
        boolean hasOrderNo = query.matches("(?s).*\\b\\d{5,8}\\b.*");
        return asksLogistics && asksDetail && hasOrderNo;
    }

    private static String compoundOrderAnswer(ToolDispatcher.Dispatch detail, ToolDispatcher.Dispatch logistics) {
        try {
            com.fasterxml.jackson.databind.JsonNode order =
                    TurnServices.TRACE_JSON.readTree(detail.json()).path("payload");
            com.fasterxml.jackson.databind.JsonNode logisticsRoot =
                    TurnServices.TRACE_JSON.readTree(logistics.json());
            String orderNo = order.path("orderNo").asText("");
            String category = order.path("categoryName").asText("");
            long amountFen = order.path("amountFen").asLong();
            String status = switch (order.path("status").asText("")) {
                case "CREATED" -> "待付款";
                case "PAID" -> "待发货";
                case "SHIPPED" -> "运输中";
                case "DELIVERED" -> "已送达";
                case "COMPLETED" -> "已完成";
                case "CANCELLED" -> "已取消";
                case "REFUNDING" -> "退款中";
                case "REFUNDED" -> "已退款";
                default -> order.path("status").asText("状态未知");
            };
            StringBuilder answer = new StringBuilder("订单 ").append(orderNo).append("：")
                    .append(category).append("，¥")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", amountFen / 100.0d))
                    .append("，当前").append(status).append("。");
            if ("OK".equals(logisticsRoot.path("status").asText())) {
                com.fasterxml.jackson.databind.JsonNode nodes = logisticsRoot.path("payload").path("nodes");
                if (nodes.isArray() && !nodes.isEmpty()) {
                    answer.append("物流最新节点：")
                            .append(nodes.get(nodes.size() - 1).path("description").asText("已更新"));
                } else {
                    answer.append("物流：暂时没有轨迹节点。");
                }
            } else {
                String message = logisticsRoot.path("message").asText("");
                answer.append("物流：").append(message.isBlank() ? "暂时没有物流信息。" : message);
            }
            return answer.toString();
        } catch (Exception unparsable) {
            return "订单与物流都已查到，但汇总时没有解析成功；请稍后再试一次。";
        }
    }

    private ModelRun runModelPath(TurnContext ctx) {
        TriageResult triage = ctx.triage();
        Intent intent = triage.intent();
        List<AgentResult.TraceStep> trace = ctx.trace();
        EventSink sink = ctx.sink();
        TokenUsage usage = ctx.usage();
        String query = ctx.query();
        sink.plan(plannedStepsFor(intent));
        String rolePrompt = ctx.route().promptSegment();
        List<LlmTypes.Message> messages = new ArrayList<>();
        messages.add(LlmTypes.Message.system(
                rolePrompt.isBlank() ? ctx.systemPrompt() : ctx.systemPrompt() + "\n" + rolePrompt));
        TurnServices.appendHistory(messages, ctx.session());

        RetrieveHandler.Retrieval retrieval = retrieve.retrieve(ctx, messages);
        if (retrieval.shortCircuit() != null) {
            String answer = retrieval.shortCircuit();
            sink.token(answer);
            persist.completeTurn(ctx, answer);
            return ModelRun.solo(new AgentResult(answer, intent, triage.layer(), CacheService.Layer.NONE,
                    List.of(), trace, null, null, false, usage.prompt(), usage.completion(), false, false,
                    services.promptCatalog.version(), List.of(), AgentResult.ContextComposition.NONE, List.of()));
        }
        if (retrieval.emptyRecall() != null) {
            return ModelRun.solo(services.fallback(AgentState.RETRIEVE, trace, sink,
                    FallbackReason.INTENT_UNRESOLVED, query, retrieval.emptyRecall(), ctx.styleTier(), usage));
        }
        HybridRetriever.Result retrieved = retrieval.retrieved();

        PlanExecuteHandler.ToolLoopResult loop = plan.execute(ctx, messages);
        if (loop.failed()) {
            return ModelRun.solo(loop.terminal());
        }

        ComposeHandler.ComposeOutcome outcome =
                compose.compose(ctx, messages, loop.lastReply(), loop.rounds(), loop.answeredByBudgetCheck(),
                        retrieved);
        if (outcome.failed()) {
            return ModelRun.solo(outcome.terminal());
        }
        String answer = outcome.answer();

        persist.completeTurn(ctx, answer);
        Optional<CacheEntry> written = persist.writeBack(ctx, retrieved, answer, loop.toolUsed());

        return new ModelRun(new AgentResult(answer, intent, triage.layer(), CacheService.Layer.NONE,
                ComposeHandler.citationIds(retrieved), trace, null, null, false, usage.prompt(), usage.completion(),
                loop.toolUsed(), false, services.promptCatalog.version(), List.copyOf(loop.planSteps()),
                ComposeHandler.contextComposition(retrieved, ctx.session(), messages),
                ComposeHandler.citationDetailsOf(retrieved)), written);
    }
}
