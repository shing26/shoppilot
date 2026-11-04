package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.gateway.llm.LlmException;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.orchestration.AgentRole;
import com.shoppilot.gateway.orchestration.ToolSurfacePolicy;
import com.shoppilot.gateway.triage.T0RuleLayer;
import com.shoppilot.gateway.triage.TriageResult;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.view.ToolStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划与工具循环阶段（round48 票 203）。
 *
 * <p>有界 Agent 状态机（ADR 0008）的执行面：规划调用、工具改绑与网关派生、槽位追问、反思配额、
 * 计划步骤观测与预算收口。判定次序、trace 文本、指标与降级出口逐行搬自原
 * {@code AgentStateMachine.runModelPath}——本类只换 owner，不改行为。
 *
 * <p>终止型结果（降级 / 追问 / 受理）由本类返回 {@link ToolLoopResult#terminal}；正常走完循环
 * 返回的最后一次模型回复交给 {@link ComposeHandler} 合成答案。
 */
final class PlanExecuteHandler {

    /** 反思的失败上下文（round35 票 144 / 票 138 规则二）。 */
    private static final String REFLECTION_CONTEXT =
            "【系统提示】上一次工具调用没有拿到业务结果（临时故障，不代表订单事实）。"
                    + "换一种做法（换工具或换参数）再试一次；也可以直接用已有信息作答。";

    private final TurnServices services;
    private final ComposeHandler compose;
    private final SlotSupport slots;

    PlanExecuteHandler(TurnServices services, ComposeHandler compose, SlotSupport slots) {
        this.services = services;
        this.compose = compose;
        this.slots = slots;
    }

    /** 循环二选一：要么已落到终止出口，要么带着最后一次模型回复进入答案合成。 */
    record ToolLoopResult(AgentResult terminal, List<LlmTypes.Message> messages, LlmTypes.Reply lastReply,
                          boolean toolUsed, List<AgentResult.PlanStep> planSteps, boolean answeredByBudgetCheck,
                          int rounds) {

        static ToolLoopResult terminal(AgentResult result) {
            return new ToolLoopResult(result, List.of(), null, false, List.of(), false, 0);
        }

        static ToolLoopResult ok(List<LlmTypes.Message> messages, LlmTypes.Reply lastReply, boolean toolUsed,
                                 List<AgentResult.PlanStep> planSteps, boolean answeredByBudgetCheck, int rounds) {
            return new ToolLoopResult(null, messages, lastReply, toolUsed, planSteps, answeredByBudgetCheck, rounds);
        }

        boolean failed() {
            return terminal != null;
        }
    }

    ToolLoopResult execute(TurnContext ctx, List<LlmTypes.Message> messages) {
        TriageResult triage = ctx.triage();
        Intent intent = triage.intent();
        String query = ctx.query();
        SessionStore.Session session = ctx.session();
        List<AgentResult.TraceStep> trace = ctx.trace();
        EventSink sink = ctx.sink();
        TokenUsage usage = ctx.usage();

        boolean toolUsed = false;
        int rounds = 0;
        // 用户明确要求办理的写动作：状态机记得它必须真的执行过，一句文字承诺不算办完。
        // **行业门也管这一格**（round35 票 146 / R11）：非电商租户的 toolsFor 是空集，模型手上根本没有
        // applyRefund / modifyDeliveryAddress 可调；这时再纠偏就是给模型递一句「直接调用 applyRefund 把它
        // 真的办掉」，与提示词第 7 条「无工具时照实说明办不了」冲突，还诱导它谎报「已提交」。
        // 同一道行业门在 toolsFor 与网关派生两处已经吃过，守卫这里是**漏吃**（ADR 0031 第 10 行的事实性修正）。
        ToolName expectedWrite = ctx.domain() == Domain.ECOMMERCE ? expectedWriteTool(intent) : null;
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
        // R14-A（2026-10-19 矩阵发现；所有者裁定 A）：读工具③失败进入反思后，若模型**不再调工具、
        // 直接作答**，失败会被吞成普通正文（实测 degraded=false、无 fallback、无工单）。这个标记在
        // 失败时置位、之后任何一次成功派发时清除；收尾前仍为真就强制 TOOL_UNAVAILABLE + 工单。
        boolean unresolvedToolFailure = false;
        while (rounds < services.properties.agent().maxToolRounds()) {
            TurnServices.step(trace, sink, AgentState.PLAN, "round=" + rounds);
            LlmTypes.Request planRequest = new LlmTypes.Request(messages, toolsFor(triage, ctx.domain(), ctx.route()),
                    services.properties.llm().temperature());
            try {
                lastReply = services.llm.complete(planRequest);
            } catch (LlmException failure) {
                return ToolLoopResult.terminal(services.fallback(AgentState.PLAN, trace, sink,
                        TurnServices.mapLlmFailure(failure), query, failure.getMessage(), ctx.styleTier(), usage));
            }
            usage.add(lastReply);
            if (!lastReply.wantsTool()) {
                if (expectedWrite == null || expectedWriteDone || nudged) {
                    break;
                }
                // 模型用文字承诺了业务动作却没调用工具（dev 评测实测形态："我这就为您提交退款申请"之后直接收尾）。
                // 只纠偏一次，且只在答案本来就不成立的请求上多花一次规划调用；工具轮次上限不变（ADR 0008）。
                nudged = true;
                services.writeNudgeCounter.increment();
                TurnServices.step(trace, sink, AgentState.PLAN, "corrective=" + expectedWrite.apiName());
                messages.add(LlmTypes.Message.assistant(lastReply.content(), List.of()));
                messages.add(LlmTypes.Message.user("你刚才承诺了要办「" + expectedWrite.description()
                        + "」，但没有调用对应工具。"
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
                services.multiToolCallCounter.increment();
            }
            LlmTypes.ToolCall rawCall = lastReply.toolCalls().get(0);
            // 计划步骤的参数表达式（ADR 0036）：只允许 {steps[i].result.<field>} 这一种形态。
            // 不合语法 / 引用未执行的前步 / 字段不存在 → 整条计划拒收，fail-closed 不执行。
            PlanExpression.Resolution resolution =
                    PlanExpression.resolve(rawCall.arguments(), stepResults, TurnServices.TRACE_JSON);
            if (resolution.rejected()) {
                services.registry.counter("shoppilot_plan_steps_total", "steps", "rejected").increment();
                TurnServices.step(trace, sink, AgentState.PLAN,
                        "plan-rejected expression=" + resolution.rejectedExpression());
                return ToolLoopResult.terminal(services.fallback(AgentState.TOOL_EXEC, trace, sink,
                        FallbackReason.TOOL_UNAVAILABLE, query,
                        "参数表达式不合法，已拒绝执行：" + resolution.rejectedExpression(), ctx.styleTier(), usage));
            }
            LlmTypes.ToolCall boundCall = bindToolToIntent(intent, rawCall, query, trace, sink);
            Map<String, Object> callArgs = new LinkedHashMap<>(resolution.arguments());
            ToolName boundTool = ToolName.fromApiName(boundCall.name());
            if (boundTool != null) {
                // 票 166：在 trace 记录 modelArgs 之前归一化详址，否则评测看到的是模型原文、
                // 执行看到的是归一化结果，两个口径对不上。
                ToolDispatcher.normalizeAddressDetail(boundTool, callArgs);
            }
            LlmTypes.ToolCall call = new LlmTypes.ToolCall(boundCall.id(), boundCall.name(), callArgs);
            messages.add(LlmTypes.Message.assistant(lastReply.content(), List.of(call)));
            long dispatchStarted = System.nanoTime();
            ToolDispatcher.Dispatch dispatch = services.dispatcher.dispatch(call, ctx.idempotencyToken(),
                    SlotSupport.dialogueText(query, session), SlotSupport.requestFingerprint(query, ctx.idempotencyToken()));
            long dispatchMillis = (System.nanoTime() - dispatchStarted) / 1_000_000L;
            if (dispatch.unknown()) {
                // 模型编出了不存在的工具：不拿 null 工具往下走，直接兜底
                return ToolLoopResult.terminal(services.fallback(AgentState.TOOL_EXEC, trace, sink,
                        FallbackReason.TOOL_UNAVAILABLE, query, "未注册的工具 " + call.name(), ctx.styleTier(), usage));
            }
            if (dispatch.fabricated()) {
                // 模型凭空编了一个订单号：绝不拿它去撞库，退回来向用户追问合法订单号
                TurnServices.step(trace, sink, AgentState.TOOL_EXEC,
                        "fabricated-orderNo 已拦截 modelArgs=" + TurnServices.traceArgs(call.arguments()));
                return ToolLoopResult.terminal(slots.askSlot(ctx, dispatch));
            }
            if (dispatch.needsSlot()) {
                TurnServices.step(trace, sink, AgentState.TOOL_EXEC,
                        dispatch.tool() + " missing=" + dispatch.missingSlots()
                                + " modelArgs=" + TurnServices.traceArgs(call.arguments()));
                return ToolLoopResult.terminal(slots.askSlot(ctx, dispatch));
            }
            // 走到这里这一枪真的派发了：记它是该工具的第几枪（票 145 的 attempt）。
            // 失败的那一枪也算——否则反思重试成功的那一步会被记成 attempt=1，与「反思回合 attempt=2」对不上。
            int attempt = toolAttempts.merge(dispatch.tool(), 1, Integer::sum);
            if (dispatch.tool() == expectedWrite) {
                expectedWriteDone = true;
            }
            TurnServices.step(trace, sink, AgentState.TOOL_EXEC,
                    dispatch.tool() + "=" + dispatch.status() + " modelArgs=" + TurnServices.traceArgs(call.arguments()));
            if (dispatch.duplicate()) {
                // 幂等命中：业务动作没有再执行，推"正在查询"是在骗用户；直接回放首次结果
                sink.duplicateSubmit(dispatch.tool(), "该请求已处理过，本次未重复执行");
            } else {
                sink.toolExecuting(dispatch.tool(), dispatch.label());
            }
            sink.toolResult(dispatch.tool(), dispatch.status(), TurnServices.summarize(dispatch));
            if (dispatch.degraded()) {
                // ③操作性失败（票 138 规则一/二）：读工具、这个工具还没用掉那次配额、且失败枪之后还有轮预算
                // → 把失败事实回填（与上面那条 assistant 的 tool_call 配对）加一条失败上下文，进下一轮重规划。
                // 这一枪**不进** stepResults/plan[]：plan 记的是真的办成的步（票 48 契约），也是
                // eval_task.py「取第一个同名工具步」这条不变量成立的前提。
                // 闸判定先于这里：①闸（NOT_FOUND / STATE_NOT_ALLOWED）、⓪受理、幂等回放、编造拦截与
                // 模型输出层错误都在别的分支上收口——反思够不到它们，重试也照过全套闸。
                if (services.reflectionEligible(dispatch.tool(), rounds, reflectedTools)) {
                    reflectedTools.add(dispatch.tool());
                    pendingReflections.add(dispatch.tool());
                    unresolvedToolFailure = true;
                    services.noteReflection(dispatch.tool(), attempt + 1, trace, sink);
                    messages.add(LlmTypes.Message.tool(call.id(), dispatch.json()));
                    messages.add(LlmTypes.Message.user(REFLECTION_CONTEXT));
                    continue;
                }
                services.noteReflectionExhausted(dispatch.tool(), reflectedTools);
                return ToolLoopResult.terminal(services.fallback(AgentState.TOOL_EXEC, trace, sink,
                        FallbackReason.TOOL_UNAVAILABLE, query, dispatch.json(), ctx.styleTier(), usage));
            }
            // R14-A：失败之后只要有一枪成功（含换工具的成功补救），失败就不再悬空。
            unresolvedToolFailure = false;
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
                return ToolLoopResult.terminal(compose.pendingApprovalAnswer(dispatch, session, query, sink, trace,
                        ctx.tenantId(), ctx.customerId(), triage.layer(), List.copyOf(planSteps), usage));
            }
            messages.add(LlmTypes.Message.tool(call.id(), dispatch.json()));
            if (failedStep(dispatch.status()) && rounds < services.properties.agent().maxToolRounds()) {
                // 前步失败即中止整条 Plan（ADR 0036）：后步的前提已不成立，不让模型继续往下调。
                // 只有"还有剩余步数"时才算中止——最后一步失败本就无后步可中止，照常进收尾轮，
                // 否则 aborted 桶会被"冗余跟进失败"灌满，失去"前提断裂"这层语义。
                planAborted = true;
                services.registry.counter("shoppilot_plan_steps_total", "steps", "aborted").increment();
                TurnServices.step(trace, sink, AgentState.TOOL_EXEC, "plan-aborted status=" + dispatch.status());
                break;
            }
        }
        if (!planAborted && toolUsed) {
            services.registry.counter("shoppilot_plan_steps_total", "steps", String.valueOf(rounds)).increment();
        }
        if (rounds >= services.properties.agent().maxToolRounds() && lastReply != null && lastReply.wantsTool()) {
            // 工具轮预算用尽（ADR 0008：硬上限 2 轮）。这里分三种走向，对齐 ADR 0008 全文：
            // ① 写动作答应过但还没真办成：轮次没了就不给"口头承诺收尾"留门，直接转人工（票 41 守卫）；
            // ② 预算外再做一次规划调用问模型还要不要工具（这是模型调用，不是第三个工具轮，
            //    与纠偏轮同例——ticket 11：rounds 只统计真正执行过的工具）：
            //    仍要 → 按字面"超限强制 FALLBACK"落工单；不要 → 它的正文就是最终答案，
            //    "先查订单再查物流"这类两轮链照常出答案（ADR 0008 Consequences）。
            if (expectedWrite != null && !expectedWriteDone) {
                services.toolRoundExhaustedCounter.increment();
                TurnServices.step(trace, sink, AgentState.PLAN,
                        "tool-rounds-exhausted write-pending=" + expectedWrite.apiName());
                return ToolLoopResult.terminal(services.fallback(AgentState.TOOL_EXEC, trace, sink,
                        FallbackReason.TOOL_ROUNDS_EXHAUSTED, query,
                        "轮次上限内未能完成 " + expectedWrite.apiName(), ctx.styleTier(), usage));
            }
            TurnServices.step(trace, sink, AgentState.PLAN, "budget-check");
            LlmTypes.Request budgetRequest = new LlmTypes.Request(messages,
                    toolsFor(triage, ctx.domain(), ctx.route()), services.properties.llm().temperature());
            try {
                lastReply = services.llm.complete(budgetRequest);
            } catch (LlmException failure) {
                return ToolLoopResult.terminal(services.fallback(AgentState.PLAN, trace, sink,
                        TurnServices.mapLlmFailure(failure), query, failure.getMessage(), ctx.styleTier(), usage));
            }
            usage.add(lastReply);
            if (lastReply.wantsTool()) {
                services.toolRoundExhaustedCounter.increment();
                TurnServices.step(trace, sink, AgentState.PLAN, "tool-rounds-exhausted");
                return ToolLoopResult.terminal(services.fallback(AgentState.TOOL_EXEC, trace, sink,
                        FallbackReason.TOOL_ROUNDS_EXHAUSTED, query,
                        "工具请求超出 " + services.properties.agent().maxToolRounds() + " 轮上限",
                        ctx.styleTier(), usage));
            }
            answeredByBudgetCheck = true;
        }

        // 行业门同样管住网关派生这一枪（ADR 0067）：酒店租户即使被判成动作意图也不许由网关
        // 代为派发电商读工具——否则"酒店不碰电商工具"会从 toolsFor 那道门旁边绕过去。
        if (rounds == 0 && !toolUsed && intent != null && intent.isAction() && ctx.domain() == Domain.ECOMMERCE) {
            // 小模型经常用自然语言追问槽位而不是发 function call。槽位状态机不能建在"模型肯不肯调工具"上，
            // 否则 maxSlotAsks 与 SLOT_UNRESOLVED 全是摆设：网关自己派生工具、自己抽槽位、自己数追问次数。
            SlotSupport.DerivedCall derived = slots.derive(intent, query);
            if (derived != null && derived.gap() != null) {
                TurnServices.step(trace, sink, AgentState.TOOL_EXEC,
                        derived.tool() + " missing=" + derived.gap().missingSlots()
                                + " modelArgs=" + TurnServices.traceArgs(derived.slots()));
                return ToolLoopResult.terminal(slots.askSlot(ctx, derived.gap()));
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
                ToolDispatcher.Dispatch dispatch = services.dispatcher.dispatch(derivedCall, ctx.idempotencyToken(),
                        SlotSupport.dialogueText(query, session));
                long derivedMillis = (System.nanoTime() - derivedStarted) / 1_000_000L;
                TurnServices.step(trace, sink, AgentState.TOOL_EXEC,
                        "gateway-derived " + dispatch.tool() + "=" + dispatch.status()
                                + " modelArgs=" + TurnServices.traceArgs(derivedCall.arguments()));
                sink.toolExecuting(dispatch.tool(), dispatch.label());
                sink.toolResult(dispatch.tool(), dispatch.status(), TurnServices.summarize(dispatch));
                LlmTypes.ToolCall lastCall = derivedCall;
                if (dispatch.degraded()) {
                    // ③操作性失败（票 138 规则一/二）在这里的动作与主循环**同规则、不同形**：派生枪是网关
                    // 自己的确定性一枪（模型刚刚明确没发 function call），没有"下一轮模型重规划"可言，
                    // 所以反思 = 带上失败事实把同一读工具重试一次。占轮、按工具一次、写工具不反思三条相同；
                    // 重试仍走同一个 dispatch（槽位 / 溯源 / 幂等 / 行业门全套照过），撞闸就走闸的原出口。
                    if (!services.reflectionEligible(derived.tool(), rounds, reflectedTools)) {
                        services.noteReflectionExhausted(derived.tool(), reflectedTools);
                        return ToolLoopResult.terminal(services.fallback(AgentState.TOOL_EXEC, trace, sink,
                                FallbackReason.TOOL_UNAVAILABLE, query, dispatch.json(), ctx.styleTier(), usage));
                    }
                    reflectedTools.add(derived.tool());
                    services.noteReflection(derived.tool(), 2, trace, sink);
                    messages.add(LlmTypes.Message.tool(derivedCall.id(), dispatch.json()));
                    messages.add(LlmTypes.Message.user(REFLECTION_CONTEXT));
                    lastCall = new LlmTypes.ToolCall("gateway-derived-retry",
                            derived.tool().apiName(), derived.slots());
                    messages.add(LlmTypes.Message.assistant(null, List.of(lastCall)));
                    rounds++;
                    derivedAttempt = 2;
                    long retryStarted = System.nanoTime();
                    dispatch = services.dispatcher.dispatch(lastCall, ctx.idempotencyToken(),
                            SlotSupport.dialogueText(query, session));
                    derivedMillis = (System.nanoTime() - retryStarted) / 1_000_000L;
                    TurnServices.step(trace, sink, AgentState.TOOL_EXEC,
                            "gateway-derived-retry " + dispatch.tool() + "=" + dispatch.status());
                    sink.toolExecuting(dispatch.tool(), dispatch.label());
                    sink.toolResult(dispatch.tool(), dispatch.status(), TurnServices.summarize(dispatch));
                    if (dispatch.degraded()) {
                        services.noteReflectionExhausted(derived.tool(), reflectedTools);
                        return ToolLoopResult.terminal(services.fallback(AgentState.TOOL_EXEC, trace, sink,
                                FallbackReason.TOOL_UNAVAILABLE, query, dispatch.json(), ctx.styleTier(), usage));
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

        // R14-A：反思已触发、但模型不再调工具、直接给出正文——失败不允许被吞成普通回答。
        // 只要失败之后没有任何一次成功派发，就按原语义收口（TOOL_UNAVAILABLE + 可查工单，ADR 0009）。
        if (unresolvedToolFailure) {
            TurnServices.step(trace, sink, AgentState.TOOL_EXEC, "tool-failure-unresolved");
            return ToolLoopResult.terminal(services.fallback(AgentState.TOOL_EXEC, trace, sink,
                    FallbackReason.TOOL_UNAVAILABLE, query, "读工具失败后模型直接作答、没有成功重试",
                    ctx.styleTier(), usage));
        }
        return ToolLoopResult.ok(messages, lastReply, toolUsed, planSteps, answeredByBudgetCheck, rounds);
    }

    /**
     * 票 164/165：意图已经确定，模型却选了订单详情查询时，按确定性意图改绑。
     *
     * <p>物流只改“纯物流问法”；改地址只改“明确要改地址”的多意图问法。
     * 改绑发生在 dispatch 前，鉴权/槽位/幂等/行业门照旧。
     */
    private LlmTypes.ToolCall bindToolToIntent(Intent intent, LlmTypes.ToolCall call, String query,
                                               List<AgentResult.TraceStep> trace, EventSink sink) {
        if (call == null) {
            return call;
        }
        boolean orderDetailCall = ToolName.QUERY_ORDER_DETAIL.apiName().equals(call.name());
        boolean logisticsCall = ToolName.QUERY_LOGISTICS.apiName().equals(call.name());
        if (!orderDetailCall && !logisticsCall) {
            return call;
        }
        if (orderDetailCall && intent == Intent.ACTION_LOGISTICS && T0RuleLayer.isLogisticsFocusedQuery(query)) {
            services.registry.counter("shoppilot_tool_binding_total",
                    "intent", "ACTION_LOGISTICS", "from", "queryOrderDetail", "to", "queryLogistics").increment();
            TurnServices.step(trace, sink, AgentState.TOOL_EXEC, "tool-bound queryLogistics from queryOrderDetail");
            return new LlmTypes.ToolCall(call.id(), ToolName.QUERY_LOGISTICS.apiName(), call.arguments());
        }
        if (orderDetailCall && intent == Intent.ACTION_ADDRESS && T0RuleLayer.isAddressModificationQuery(query)) {
            services.registry.counter("shoppilot_tool_binding_total",
                    "intent", "ACTION_ADDRESS", "from", "queryOrderDetail", "to", "modifyDeliveryAddress")
                    .increment();
            TurnServices.step(trace, sink, AgentState.TOOL_EXEC,
                    "tool-bound modifyDeliveryAddress from queryOrderDetail");
            return new LlmTypes.ToolCall(call.id(), ToolName.MODIFY_DELIVERY_ADDRESS.apiName(), call.arguments());
        }
        if (logisticsCall && intent == Intent.ACTION_ORDER && T0RuleLayer.isOrderDetailFocusedQuery(query)) {
            services.registry.counter("shoppilot_tool_binding_total",
                    "intent", "ACTION_ORDER", "from", "queryLogistics", "to", "queryOrderDetail").increment();
            TurnServices.step(trace, sink, AgentState.TOOL_EXEC, "tool-bound queryOrderDetail from queryLogistics");
            return new LlmTypes.ToolCall(call.id(), ToolName.QUERY_ORDER_DETAIL.apiName(), call.arguments());
        }
        if (logisticsCall && intent == Intent.ACTION_REFUND && T0RuleLayer.isExplicitRefundRequest(query)) {
            services.registry.counter("shoppilot_tool_binding_total",
                    "intent", "ACTION_REFUND", "from", "queryLogistics", "to", "applyRefund").increment();
            TurnServices.step(trace, sink, AgentState.TOOL_EXEC, "tool-bound applyRefund from read query");
            return new LlmTypes.ToolCall(call.id(), ToolName.APPLY_REFUND.apiName(), call.arguments());
        }
        return call;
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
    private List<Map<String, Object>> toolsFor(TriageResult triage, Domain domain, AgentRole route) {
        return ToolSurfacePolicy.toolsFor(route, domain, triage.intent());
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
}
