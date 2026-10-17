package com.shoppilot.gateway.knowledge;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.tool.Intent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「本行业、本意图是否存在店铺级条款」的只读探针（2026-10-17 跨店同问同答缺陷修复）。
 *
 * <p>它回答的是缓存平台桶**能不能跨店共享**的前置条件。一个答案就算只引用平台条款，
 * 只要同行业里有任何一个店铺对同一意图备了店铺级条款（例如 T002 的死蟹细则、T001 的延长
 * 退货窗口），它对该店铺就不成立；而写方在自己的可见范围里看不到别家的店铺条款——
 * "引用全是平台条款"从来不足以证明"这份答案对各店都成立"（实机复现：T001 先问
 * 「死蟹怎么赔付」，T002 再问同句直接命中平台桶里 T001 的答案，自己的门店细则永远不生效）。
 * 所以共享安全性要按行业问一次知识库，而不是按写方自己的引用猜。
 *
 * <p>查询打在词法索引上：scope=SHOP + domain + intent + 当前纪元，size=1，只问"有没有"。
 * 结果按 (纪元, 行业, 意图) 记忆——语料换代（纪元推进）时整体作废。
 * 探针失败按 **fail-closed** 处理（当作存在店铺级条款 → 不共享）：隔离比命中率值钱。
 */
@Component
public class ShopRuleIndex {

    private static final Logger log = LoggerFactory.getLogger(ShopRuleIndex.class);

    private final EsRestClient es;
    private final KbEpoch kbEpoch;
    private final String index;

    /** 键 = 行业|意图，值 = 该行业该意图是否有店铺级条款；纪元换代时整体清空。 */
    private final ConcurrentHashMap<String, Boolean> memo = new ConcurrentHashMap<>();
    private volatile long memoEpoch = Long.MIN_VALUE;

    public ShopRuleIndex(EsRestClient es, KbEpoch kbEpoch, GatewayProperties properties) {
        this.es = es;
        this.kbEpoch = kbEpoch;
        this.index = properties.retrieval().esIndex();
    }

    /**
     * 该行业该意图是否存在店铺级条款。
     *
     * @param intent null（判定未定案）按"存在"处理：没有意图就没有可见范围可谈，不共享才是安全默认
     */
    public boolean hasShopScopedRules(Domain domain, Intent intent) {
        if (intent == null) {
            return true;
        }
        long epoch = kbEpoch.current();
        if (memoEpoch != epoch) {
            memo.clear();
            memoEpoch = epoch;
        }
        Domain effective = domain == null ? Domain.UNKNOWN : domain;
        return memo.computeIfAbsent(effective.name() + "|" + intent.name(),
                key -> probe(effective, intent, epoch));
    }

    private boolean probe(Domain domain, Intent intent, long epoch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", 1);
        body.put("_source", List.of("ruleId"));
        body.put("query", Map.of("bool", Map.of("filter", List.of(
                Map.of("term", Map.of("scope", RuleChunk.SCOPE_SHOP)),
                Map.of("term", Map.of("domain", domain.name())),
                Map.of("term", Map.of("intent", intent.name())),
                Map.of("term", Map.of("kbEpoch", epoch))))));
        try {
            boolean present = !es.searchRaw(index, body).isEmpty();
            log.info("店铺级条款探针：domain={} intent={} epoch={} 存在={}", domain, intent, epoch, present);
            return present;
        } catch (RuntimeException esUnavailable) {
            // 探针拿不准时宁可少共享：把平台桶关掉只是多存几份本店答案，串店答案是事故
            log.warn("店铺级条款探针失败，按存在处理（本意图不跨店共享）: domain={} intent={} 原因={}",
                    domain, intent, esUnavailable.getMessage());
            return true;
        }
    }
}
