package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheEntry;
import com.shoppilot.gateway.cache.WriteBackPolicy;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 落库阶段（round48 票 203）。
 *
 * <p>会话推进与缓存写回都在这里：写回判定仍是既有 {@link WriteBackPolicy}，异步提交仍走
 * {@link com.shoppilot.gateway.cache.WriteBackPool}，降级检索不写负缓存这条防线原样保留。
 * 本类不决定"答案对不对"，只把已经定稿的答案落到会话与缓存。
 */
final class PersistHandler {

    /** 异步写回失败只 warn，不影响本次响应；日志归属随写回提交点一起搬到本类。 */
    private static final Logger log = LoggerFactory.getLogger(PersistHandler.class);

    private final TurnServices services;

    PersistHandler(TurnServices services) {
        this.services = services;
    }

    /** 一轮问答已经完成：把买家问题与最终答案追加进会话，并清掉待办槽位。 */
    void completeTurn(TurnContext ctx, String answer) {
        services.sessionStore.save(ctx.tenantId(), ctx.customerId(),
                services.sessionStore.appendTurn(TurnServices.clearPending(ctx.session()), ctx.query(), answer));
    }

    /**
     * 缓存写回（原文搬运）。只有真正写回的那条答案会被调用方拿去广播给 singleflight 等待者；
     * 降级话术、拒写与无写回目标都返回 empty。
     */
    Optional<CacheEntry> writeBack(TurnContext ctx, HybridRetriever.Result retrieved, String answer,
                                   boolean toolUsed) {
        List<String> citations = ComposeHandler.citationIds(retrieved);
        List<String> citedScopes = retrieved == null ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::scope).toList();
        // 与 citations 同源同序的标题与出处：买家端引用详情要用它们（round33 / ADR 0067，出处 2026-10-17 补）。
        // 命中路径不跑检索，这两份必须随缓存条目一起落库，否则缓存命中时买家端又只剩"政策条款"。
        List<String> citedHeadings = retrieved == null ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::title).toList();
        List<String> citedDocs = retrieved == null ? List.of()
                : retrieved.rules().stream().map(HybridRetriever.Retrieved::sourceDoc).toList();

        WriteBackPolicy.Verdict verdict = services.writeBackPolicy.evaluate(new WriteBackPolicy.Request(
                ctx.triage().intent(), answer, retrieved != null && !retrieved.empty(), toolUsed,
                retrieved != null && retrieved.degraded(), false));
        Optional<CacheEntry> written = Optional.empty();
        if (verdict.eligible()) {
            Optional<CacheEntry> prepared = services.cacheService.prepareWrite(
                    ctx.tenantId(), ctx.domain(), ctx.triage().intent(), ctx.epoch(), ctx.lookup(), answer,
                    citedScopes, citations, citedHeadings, citedDocs, services.properties.llm().model());
            if (prepared.isEmpty()) {
                // 准入判定没给出向量（例如缓存被关），写回无从落点
                TurnServices.step(ctx.trace(), ctx.sink(), AgentState.CACHE_WRITE, "skipped:no-write-target");
            } else {
                // scope 进 trace：平台桶 vs 本店桶是坏缓存分区的唯一现场读数，
                // 排查"跨店同问同答"时不用去翻 Redis key（2026-10-17 修复时加）
                TurnServices.step(ctx.trace(), ctx.sink(), AgentState.CACHE_WRITE,
                        "eligible scope=" + prepared.get().scope());
                written = prepared;
                CacheEntry entry = prepared.get();
                // 坐标包装在 WriteBackPool.submit 内部完成（票 27）：提交点不再各自记得 wrap
                services.writeBackPool.submit(() -> {
                    try {
                        services.cacheService.writeBack(entry, ctx.lookup());
                    } catch (Exception failure) {
                        log.warn("异步写回失败，不影响本次响应: {}", failure.getMessage());
                    }
                });
            }
        } else {
            TurnServices.step(ctx.trace(), ctx.sink(), AgentState.CACHE_WRITE, "rejected:" + verdict.reason());
            // "空召回且未降级"的负标记写在检索出口里（政策意图到不了这里）。
            // 这里只剩下检索降级的一格：检索引擎挂了不等于库里没有，写负缓存等于把一次抖动
            // 固化成 60 秒的批量转人工——2026-09-08 mix80 压测里就是这样把自己打停的。
            if (retrieved != null && retrieved.empty() && retrieved.degraded()) {
                services.negativeSuppressedCounter.increment();
                TurnServices.step(ctx.trace(), ctx.sink(), AgentState.CACHE_WRITE,
                        "negative-suppressed:retrieval-degraded");
            }
        }
        return written;
    }
}
