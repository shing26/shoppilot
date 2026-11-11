package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.cache.QueryNormalizer;
import com.shoppilot.gateway.llm.LlmException;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.style.StyleService;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.view.ToolStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性的槽位小工具与追问状态机（round48 票 203 从 AgentStateMachine 原样搬出）。
 *
 * <p>两半：静态那半是"能正则定死的绝不打模型"的解析器，服务派生、续办与复合三条路径；
 * 实例那半持有依赖，承接槽位追问 / 重复追问分相 / 网关派生判定 / 续办重放。判定与计数逐行未改。
 */
final class SlotSupport {

    private static final Pattern ORDER_NO = Pattern.compile("(?<!\\d)(\\d{5,8})(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");

    /** 每个工具里"正则能确定判缺"的槽位；不在表里的槽位缺失不触发网关侧追问。 */
    private static final Map<ToolName, List<String>> GATEWAY_CONFIDENT_SLOTS = Map.of(
            ToolName.QUERY_ORDER_DETAIL, List.of("orderNo"),
            ToolName.QUERY_LOGISTICS, List.of("orderNo"),
            ToolName.APPLY_REFUND, List.of("orderNo"),
            ToolName.MODIFY_DELIVERY_ADDRESS, List.of("orderNo", "receiverPhone"));

    private final TurnServices services;
    private final ComposeHandler compose;

    SlotSupport(TurnServices services, ComposeHandler compose) {
        this.services = services;
        this.compose = compose;
    }

    // ── 静态解析 ──────────────────────────────────────────────────────────

    /** 槽位抽取：能正则定死的绝不打模型，这一条同时服务派生路径与续办路径。 */
    static Map<String, Object> extractSlots(ToolName tool, String query) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        Matcher matcher = ORDER_NO.matcher(query);
        if (matcher.find()) {
            arguments.put("orderNo", matcher.group(1));
        }
        if (tool == ToolName.MODIFY_DELIVERY_ADDRESS) {
            Matcher phone = PHONE.matcher(query);
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
     * Handoff / 落单 payload 的已确认槽位（H2）。只收买家本话说出的标识，不收检索条款或助手编造值；
     * 电话进 payload 前打码，避免编排观测面变成第二份 PII 账本。
     */
    static Map<String, String> confirmedSlots(Intent intent, String query) {
        Map<String, String> slots = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return slots;
        }
        Matcher order = ORDER_NO.matcher(query);
        if (order.find()) {
            slots.put("orderNo", order.group(1));
        }
        if (intent == Intent.ACTION_ADDRESS) {
            Matcher phone = PHONE.matcher(query);
            if (phone.find()) {
                slots.put("receiverPhone", maskPhone(phone.group(1)));
            }
        }
        return slots;
    }

    static String maskPhone(String phone) {
        return phone.length() <= 7 ? phone.substring(0, Math.max(1, phone.length() - 2)) + "**"
                : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 请求指纹：本轮 query 归一化后的哈希（ADR 0046 票 58）。
     *
     * <p>只在客户端**显式**带了 token 时才算，否则返回 null。两条理由：派生 token 在模型之前算不出来
     * （它要工具参数），而"客户端复用旧 token 却换了要求"这个问题也只在显式 token 下存在。传 null 时
     * 幂等层既不写、也不查请求级索引 —— 行为与 ADR 0008 时期逐字一致。
     */
    static String requestFingerprint(String query, String idempotencyToken) {
        if (idempotencyToken == null || idempotencyToken.isBlank()) {
            return null;
        }
        return QueryNormalizer.md5(QueryNormalizer.normalize(query));
    }

    static String dialogueText(String query, SessionStore.Session session) {
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

    // ── 槽位追问 ──────────────────────────────────────────────────────────

    /** 缺槽位：追问一次，仍缺则转人工。绝不猜（ADR 0008、ticket 11）。 */
    AgentResult askSlot(TurnContext ctx, ToolDispatcher.Dispatch dispatch) {
        SessionStore.Session session = ctx.session();
        int asks = session.slotAskCount() + 1;
        if (asks > services.properties.agent().maxSlotAsks()) {
            return services.fallback(AgentState.SLOT_ASK, ctx.trace(), ctx.sink(), FallbackReason.SLOT_UNRESOLVED,
                    ctx.query(), "已追问 " + session.slotAskCount() + " 次仍缺槽位", ctx.styleTier(), ctx.usage());
        }
        String slot = dispatch.missingSlots().get(0);
        String question = services.dispatcher.question(dispatch.tool(), dispatch.missingSlots());
        if (session.slotAskCount() > 0) {
            recordRepeatedSlotAsk(session, slot);
        }
        TurnServices.step(ctx.trace(), ctx.sink(), AgentState.SLOT_ASK, dispatch.tool() + " 缺 " + slot);
        ctx.sink().slotAsk(slot, question);
        services.sessionStore.save(ctx.tenantId(), ctx.customerId(), new SessionStore.Session(ctx.conversationId(),
                session.turns(), dispatch.tool().apiName(), new LinkedHashMap<>(Map.of("askedSlot", slot)), asks,
                session.currentRole(), session.handoffTrail()));
        return new AgentResult(question, dispatch.tool().intent(), "SLOT", CacheService.Layer.NONE,
                List.of(), ctx.trace(), null, null, true, ctx.usage().prompt(), ctx.usage().completion(),
                false, false, services.promptCatalog.version(), List.of(),
                AgentResult.ContextComposition.NONE, List.of());
    }

    /**
     * H4 friction 口径：重复追问必须分相。
     *
     * <p>same_role = 本 session 还没有 handoff trail，重复追问大概率是买家补充/改主意；
     * cross_handoff + sameSlot = 已经发生 Handoff 后仍把同一格槽位问第二遍，另计
     * {@code shoppilot_handoff_context_loss_total}。phase 用 trail 是否为空判定，不猜角色意图。
     */
    void recordRepeatedSlotAsk(SessionStore.Session session, String slot) {
        String previousSlot = String.valueOf(session.pendingArgs().getOrDefault("askedSlot", ""));
        boolean sameSlot = slot.equals(previousSlot);
        String phase = session.handoffTrail().isEmpty() ? "same_role" : "cross_handoff";
        services.registry.counter("shoppilot_quality_slot_asks_repeated_total",
                "phase", phase, "sameSlot", String.valueOf(sameSlot)).increment();
        if ("cross_handoff".equals(phase) && sameSlot) {
            services.registry.counter("shoppilot_handoff_context_loss_total", "slot", slot).increment();
        }
    }

    /** 网关自己派生出的工具调用：missing 非空即追问，missing 为空且是读工具即可代为执行。 */
    record DerivedCall(ToolName tool, Map<String, Object> slots, List<String> missing,
                       ToolDispatcher.Dispatch gap) {
    }

    /**
     * 模型这一轮只发了自然语言、没发 function call 时，由网关自己派生工具并检查槽位缺口。
     *
     * <p>只接管 GATEWAY_CONFIDENT_SLOTS 里能靠正则定死的那几格：改地址的省/市/区/详址与收件人
     * 必须交给模型抽，用正则判它们缺失等于把用户已经写出来的地址再问一遍。
     *
     * @return null 表示不接管；否则 gap 非空即追问，槽位齐备且是读工具时才允许代为执行
     */
    DerivedCall derive(Intent intent, String query) {
        ToolName tool = ToolName.forIntent(intent);
        if (tool == null) {
            return null;
        }
        Map<String, Object> slots = extractSlots(tool, query);
        List<String> missing = services.dispatcher.missingSlots(tool, slots);
        List<String> confident = GATEWAY_CONFIDENT_SLOTS.getOrDefault(tool, List.of());
        List<String> actionable = missing.stream().filter(confident::contains).toList();
        return new DerivedCall(tool, slots, missing,
                actionable.isEmpty() ? null
                        : new ToolDispatcher.Dispatch(tool, null, null, actionable, null, false, false));
    }

    // ── 续办 ──────────────────────────────────────────────────────────────

    /**
     * 上一轮追问过槽位，这一轮把用户补充的信息与原始诉求合并后重放工具。
     * 这里刻意不再进模型：订单号这类槽位用正则就能取，交给模型只会更慢更贵。
     */
    AgentResult resumePending(SessionStore.Session session, String query, String idempotencyToken,
                              String tenantId, String customerId, String conversationId,
                              List<AgentResult.TraceStep> trace, EventSink sink, String systemPrompt,
                              StyleService.Tier styleTier, TokenUsage usage) {
        TurnServices.step(trace, sink, AgentState.SLOT_ASK, "合并补充信息");
        ToolName tool = ToolName.fromApiName(session.pendingTool());
        if (tool == null) {
            services.sessionStore.save(tenantId, customerId, TurnServices.clearPending(session));
            return services.fallback(AgentState.SLOT_ASK, trace, sink, FallbackReason.INTENT_UNRESOLVED, query,
                    "待办工具已失效", styleTier, usage);
        }
        Map<String, Object> arguments = extractSlots(tool, query);
        List<String> missing = services.dispatcher.missingSlots(tool, arguments);
        if (!missing.isEmpty()) {
            String slot = missing.get(0);
            String question = services.dispatcher.question(tool, missing);
            int asks = session.slotAskCount() + 1;
            if (asks > services.properties.agent().maxSlotAsks()) {
                return services.fallback(AgentState.SLOT_ASK, trace, sink, FallbackReason.SLOT_UNRESOLVED, query,
                        null, styleTier, usage);
            }
            sink.slotAsk(slot, question);
            recordRepeatedSlotAsk(session, slot);
            services.sessionStore.save(tenantId, customerId, new SessionStore.Session(conversationId,
                    session.turns(), tool.apiName(), arguments, asks, session.currentRole(),
                    session.handoffTrail()));
            return new AgentResult(question, tool.intent(), "SESSION", CacheService.Layer.NONE, List.of(), trace,
                    null, null, true, usage.prompt(), usage.completion(), false, false,
                    services.promptCatalog.version(), List.of(), AgentResult.ContextComposition.NONE, List.of());
        }
        LlmTypes.ToolCall call = new LlmTypes.ToolCall("resumed", tool.apiName(), arguments);
        ToolDispatcher.Dispatch dispatch = services.dispatcher.dispatch(call, idempotencyToken,
                dialogueText(query, session), requestFingerprint(query, idempotencyToken));
        TurnServices.step(trace, sink, AgentState.TOOL_EXEC, tool + "=" + dispatch.status());
        if (dispatch.duplicate()) {
            sink.duplicateSubmit(tool, "该请求已处理过，本次未重复执行");
        } else {
            sink.toolExecuting(tool, dispatch.label());
        }
        sink.toolResult(tool, dispatch.status(), TurnServices.summarize(dispatch));
        if (dispatch.status() == ToolStatus.PENDING_APPROVAL) {
            return compose.pendingApprovalAnswer(dispatch, session, query, sink, trace, tenantId, customerId,
                    "SESSION", List.of(), usage);
        }
        services.sessionStore.save(tenantId, customerId, TurnServices.clearPending(session));
        // ③操作性失败（票 138 规则一/二）：续办枪与派生枪同形——工具与槽位都是确定性得来的，没有"下一轮
        // 模型重规划"可言，所以反思 = 同一读工具带失败事实重试一次（占轮、按工具一次、写工具不反思）。
        // 这条路径只可能是读工具（写工具的待办在 derive 与 askSlot 那两处就被挡下），
        // 但读工具的判断仍走同一个 reflectionEligible，不在这里另开一套。
        String firstFailure = null;
        if (dispatch.degraded()) {
            Set<ToolName> reflectedTools = new HashSet<>();
            if (!services.reflectionEligible(tool, 1, reflectedTools)) {
                services.noteReflectionExhausted(tool, reflectedTools);
                return services.fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE, query,
                        dispatch.json(), styleTier, usage);
            }
            reflectedTools.add(tool);
            firstFailure = dispatch.json();
            services.noteReflection(tool, 2, trace, sink);
            dispatch = services.dispatcher.dispatch(call, idempotencyToken, dialogueText(query, session),
                    requestFingerprint(query, idempotencyToken));
            TurnServices.step(trace, sink, AgentState.TOOL_EXEC, tool + "=retry " + dispatch.status());
            sink.toolExecuting(tool, dispatch.label());
            sink.toolResult(tool, dispatch.status(), TurnServices.summarize(dispatch));
            if (dispatch.degraded()) {
                services.noteReflectionExhausted(tool, reflectedTools);
                return services.fallback(AgentState.TOOL_EXEC, trace, sink, FallbackReason.TOOL_UNAVAILABLE, query,
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
            // 票 217：续办追问的第二跳同样逐 token 直推买家，必须过同一个流式守卫。
            AnswerEgressGuard.Streaming egress = new AnswerEgressGuard.Streaming();
            LlmTypes.Reply reply = services.llm.stream(new LlmTypes.Request(messages, List.of(),
                    services.properties.llm().temperature()), delta -> {
                        String safe = egress.accept(delta);
                        if (!safe.isEmpty()) {
                            sink.token(safe);
                        }
                    });
            String tail = egress.flush();
            if (!tail.isEmpty()) {
                sink.token(tail);
            }
            AnswerEgressGuard.count(services.registry, "slot_resume", egress.hits());
            usage.add(reply);
            AnswerEgressGuard.Result guarded = AnswerEgressGuard.sanitize(reply.content());
            AnswerEgressGuard.count(services.registry, "slot_resume", guarded.hits());
            String answer = guarded.text();
            services.sessionStore.save(tenantId, customerId,
                    services.sessionStore.appendTurn(TurnServices.clearPending(session), query, answer));
            return new AgentResult(answer, tool.intent(), "SESSION", CacheService.Layer.NONE, List.of(),
                    trace, null, null, false, usage.prompt(), usage.completion(), true, false,
                    services.promptCatalog.version(), List.of(), AgentResult.ContextComposition.NONE, List.of());
        } catch (LlmException failure) {
            return services.fallback(AgentState.REPLY, trace, sink, TurnServices.mapLlmFailure(failure), query,
                    failure.getMessage(), styleTier, usage);
        }
    }
}
