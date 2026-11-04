package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.tool.Intent;

import java.util.List;

/**
 * 检索阶段（round48 票 203）。
 *
 * <p>只做三件事：判断这条请求该不该检索、把条款拼成 {@code 【政策条款】} 用户消息、以及
 * "跑完了确实 0 条"时的确定性出口。模型循环、答案合成与写回都不在这里。
 * 判定条件逐行搬自原 {@code AgentStateMachine.runModelPath}，包括 UNKNOWN 的政策形状兜底与
 * 空召回不写负缓存的降级例外。
 */
final class RetrieveHandler {

    /** 单条条款注入上限：防止一次检索把 Prompt 撑爆，也保证 5 条条款可控。 */
    private static final int MAX_CLAUSE_CHARS = 600;

    private final TurnServices services;

    RetrieveHandler(TurnServices services) {
        this.services = services;
    }

    /**
     * @param retrieved   检索结果；未检索时为 null
     * @param shortCircuit 非 null 表示本轮不调模型、直接使用这句确定性回答
     * @param emptyRecall 非 null 表示政策意图检索完成且 0 条未降级，调用方走确定性转人工
     */
    record Retrieval(HybridRetriever.Result retrieved, String shortCircuit, String emptyRecall) {

        static Retrieval proceed(HybridRetriever.Result retrieved) {
            return new Retrieval(retrieved, null, null);
        }

        static Retrieval shortCircuit(String answer) {
            return new Retrieval(null, answer, null);
        }

        static Retrieval emptyRecall(String detail) {
            return new Retrieval(null, null, detail);
        }

        boolean isProceed() {
            return shortCircuit == null && emptyRecall == null;
        }
    }

    Retrieval retrieve(TurnContext ctx, List<LlmTypes.Message> messages) {
        String query = ctx.query();
        Intent intent = ctx.triage().intent();
        // 只有政策咨询才做条款检索（round37）。UNKNOWN 是「判定不出的诉求」，此前也走检索，
        // 结果是任何一句通用问题都会被灌进 5 条不相关政策（实测「店铺推荐什么商品」召回了
        // 冷链、死蟹、价保等条款），模型要么照着念、要么被带偏。这类问题交给店铺人格 + 模型
        // 常识作答，不引用条款、也不进缓存。
        //
        // 但「分类错误」不能成为绕过安全出口的路：T0/T1 把一句政策诉求误判成 UNKNOWN 时
        // （gold 的 UNKNOWN 读数本来就不是 100%），再加一道零 token 的**政策形状**判定，
        // 命中就仍走检索与空召回出口；一般问答（推荐商品、问店名）没有政策词，不受影响。
        boolean policyIntent = intent != null && intent.isPolicy();
        // 票 166 复跑发现：动作意图 + 政策形状（"坏了怎么赔，先看物流"）会被这条 0-token
        // 追问短路，工具根本不出场。它只在 UNKNOWN 时才是安全网，不能抢动作诉求。
        boolean policyHint = !policyIntent && (intent == null || intent == Intent.UNKNOWN)
                && services.triageEngine.looksPolicyShaped(query);
        // 政策形状命中时尽量给一个具体意图分区：按 null 检索会把别类条款一起捞进来。
        Intent hintIntent = policyHint ? services.triageEngine.policyHintIntent(query) : null;
        if (policyHint && hintIntent == null) {
            // 泛化的“有哪些售后保障”没有具体分区：模型会把平台规则与本店规则合并成更宽的承诺
            // （实测把“水果蔬菜鲜花 7 天无理由 / 换货两次”说给鲜果驿站）。这里不检索、不调模型，
            // 用一句确定性追问收口；具体问题（生鲜腐烂、七天无理由、酒店退改）仍走正常政策路径。
            String answer = "本店售后政策按商品类型和问题类型不同（比如生鲜腐烂、七天无理由、"
                    + "物流破损、退款时效）。请告诉我具体商品或订单号，以及遇到的问题，我再按对应条款答复。";
            TurnServices.step(ctx.trace(), ctx.sink(), AgentState.RETRIEVE, "policy-hint:need-detail");
            return Retrieval.shortCircuit(answer);
        }

        HybridRetriever.Result retrieved = null;
        if (policyIntent || policyHint) {
            TurnServices.step(ctx.trace(), ctx.sink(), AgentState.RETRIEVE,
                    "intent=" + intent + (policyHint ? " policy-hint" : ""));
            try {
                retrieved = services.retriever.retrieve(query, ctx.tenantId(), ctx.domain(),
                        policyIntent ? intent : hintIntent);
            } catch (RuntimeException retrievalFailure) {
                TurnServices.warnRetrievalFailure(retrievalFailure);
                retrieved = HybridRetriever.Result.unavailable(ctx.epoch());
            }
            TurnServices.step(ctx.trace(), ctx.sink(), AgentState.RETRIEVE,
                    "dense=" + retrieved.denseHits() + " lexical=" + retrieved.lexicalHits()
                            + " fused=" + retrieved.rules().size() + " degraded=" + retrieved.degraded());
        } else {
            TurnServices.step(ctx.trace(), ctx.sink(), AgentState.RETRIEVE, "skipped:general-chat");
        }
        // 空召回不是答案（2026-10-17 实机复现后定：与跨店同问同答同批修复）。
        // 政策意图在可见范围里检索不到任何条款时，此前把模型自由发挥的正文直接当答案返回——
        // 酒店租户会编出"死蟹全额退款""无理由退货 15 天"这类本店并不存在的承诺，零引用、无降级、
        // 不落工单；同一句第二次问才因负缓存转人工，首问与复问的口径自相矛盾。这与 ADR 0009
        // 「办不成就给一句实话并落可查工单」、ADR 0067「宁可转人工，不可拿另一个行业的条款回答」
        // 直接冲突。改成与负缓存同一出口：确定性转人工 + 落单 + 写负标记，模型不再有机会发挥。
        // 只在"跑完了、确实 0 条"时触发；检索降级（degraded）仍走原路——把一次抖动固化成
        // 批量转人工是 2026-09-08 压测里踩过的坑，不在本出口的范围内。
        if ((policyIntent || policyHint) && retrieved != null && retrieved.empty() && !retrieved.degraded()) {
            // 负标记只写具体政策意图：UNKNOWN 分区不承接政策问句，写进去会污染一般问答的负缓存。
            if (policyIntent && ctx.lookup().normalizedQuery() != null
                    && !ctx.lookup().normalizedQuery().isEmpty()) {
                services.cacheService.writeNegative(ctx.tenantId(), ctx.domain(), intent, ctx.epoch(), ctx.lookup());
            }
            TurnServices.step(ctx.trace(), ctx.sink(), AgentState.RETRIEVE,
                    "empty-recall:可见范围内无该意图条款" + (policyHint ? " (policy-hint)" : ""));
            return Retrieval.emptyRecall("可见范围内没有该诉求的政策条款（空召回）");
        }
        messages.add(LlmTypes.Message.user(composeUserMessage(query, retrieved)));
        return Retrieval.proceed(retrieved);
    }

    private String composeUserMessage(String query, HybridRetriever.Result retrieved) {
        // 一般问答（round37）：没有检索面就不加「未检索到条款」这句，避免模型把它读成
        // 「本店没有相关信息，应该转人工」。店铺资料已经在系统提示词里。
        if (retrieved == null) {
            return query;
        }
        if (retrieved.empty()) {
            return "【政策条款】\n（本轮未检索到相关条款）\n\n【买家问题】\n" + query;
        }
        StringBuilder builder = new StringBuilder("【政策条款】\n");
        int index = 1;
        for (HybridRetriever.Retrieved rule : retrieved.rules()) {
            // scope 必须让模型看见：同一事项平台与本店口径不同时，要优先本店条款（T002 生鲜不退
            // vs 平台七天无理由就是这一格）。否则模型只能把两条都念一遍，甚至合并成更宽的承诺。
            String scope = "SHOP".equalsIgnoreCase(rule.scope()) ? "本店" : "平台";
            builder.append(index++).append(". [").append(scope).append("] [").append(rule.ruleId()).append("] ")
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
}
