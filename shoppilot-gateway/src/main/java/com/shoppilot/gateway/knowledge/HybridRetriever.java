package com.shoppilot.gateway.knowledge;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.tool.Intent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 双引擎混合检索：Qdrant 稠密召回 + ES BM25 召回，RRF 融合（ADR 0010）。
 *
 * <p>租户可见范围 = 本店铺规则 + 平台规则，两者共享同一份平台条目，
 * 不按店铺复制 N 份（ADR 0004）。
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final QdrantRestClient qdrant;
    private final EsRestClient es;
    private final EmbeddingClient embedding;
    private final KbEpoch kbEpoch;
    private final GatewayProperties.Retrieval config;
    private final Timer denseTimer;
    private final Timer lexicalTimer;

    public HybridRetriever(QdrantRestClient qdrant, EsRestClient es, EmbeddingClient embedding, KbEpoch kbEpoch,
                           GatewayProperties properties, MeterRegistry registry) {
        this.qdrant = qdrant;
        this.es = es;
        this.embedding = embedding;
        this.kbEpoch = kbEpoch;
        this.config = properties.retrieval();
        this.denseTimer = Timer.builder("shoppilot_retrieve_dense_seconds").register(registry);
        this.lexicalTimer = Timer.builder("shoppilot_retrieve_lexical_seconds").register(registry);
    }

    public Result retrieve(String query, String tenantId, Intent intent) {
        long epoch = kbEpoch.current();
        List<String> visibleTenants = List.of(tenantId, RuleChunk.PLATFORM_TENANT);
        Recalls recalls = recall(query, visibleTenants, intent, epoch);
        List<Scored> dense = recalls.dense();
        List<Scored> lexical = recalls.lexical();

        // 意图过滤过严导致召回为空时退回不带意图的检索：宁可噪声高，不可答不出
        if (dense.isEmpty() && lexical.isEmpty() && intent != null && intent.cacheAdmissible()) {
            recalls = recall(query, visibleTenants, null, epoch);
            dense = recalls.dense();
            lexical = recalls.lexical();
        }

        List<Retrieved> fused = rrf(dense, lexical);
        return new Result(fused.subList(0, Math.min(config.fusedTopK(), fused.size())),
                dense.size(), lexical.size(), epoch, recalls.degraded());
    }

    private Recalls recall(String query, List<String> visibleTenants, Intent intent, long epoch) {
        Path dense = timed(denseTimer, () -> run("稠密召回", () -> denseRecall(query, visibleTenants, intent, epoch)));
        Path lexical = timed(lexicalTimer,
                () -> run("词法召回", () -> lexicalRecall(query, visibleTenants, intent, epoch)));
        return new Recalls(dense.hits(), lexical.hits(), dense.failed() || lexical.failed());
    }

    /**
     * 单路失效由另一路独扛，但"这一路挂了"与"这一路查到 0 条"必须留下区别。
     *
     * <p>区别丢掉的代价在压测里现形过：Ollama 被打爆时稠密路全部超时，返回的空列表被上层当成
     * "库里确实没有这条政策"，于是写进 60 秒负缓存，同问题的后续请求直接短路成转人工——
     * 一次中间件抖动被固化成 60 秒的自己制造的服务中断。
     */
    private Path run(String label, Supplier<List<Scored>> action) {
        try {
            return new Path(action.get(), false);
        } catch (RuntimeException failure) {
            log.warn("{}不可用，本轮由另一路独扛: {}", label, failure.getMessage());
            return new Path(List.of(), true);
        }
    }

    /**
     * 检索质量对比用：把两路召回的原始名次如实吐出来，供 {@code scripts/retrieval_compare.py}
     * 生成 dense-only 与 hybrid 的 top-K 差异表（ticket 08 验收项）。
     *
     * <p>刻意走"一次检索、两种排法"而不是加一个 dense-only 开关再跑两遍：
     * 跑两遍会各自触发一次向量入库与一次意图兜底重试，两行数据不可比；
     * 从同一次召回结果里分别取序，比较的才只是融合这一步带来的差别。
     */
    public Diagnosis diagnose(String query, String tenantId, Intent intent, int limit) {
        long epoch = kbEpoch.current();
        List<String> visibleTenants = List.of(tenantId, RuleChunk.PLATFORM_TENANT);
        Recalls recalls = recall(query, visibleTenants, intent, epoch);
        List<Scored> dense = recalls.dense();
        List<Scored> lexical = recalls.lexical();
        boolean usedFallback = false;
        if (dense.isEmpty() && lexical.isEmpty() && intent != null && intent.cacheAdmissible()) {
            recalls = recall(query, visibleTenants, null, epoch);
            dense = recalls.dense();
            lexical = recalls.lexical();
            usedFallback = true;
        }
        return new Diagnosis(topIds(dense, limit), topIds(lexical, limit),
                topIdsFromFused(rrf(dense, lexical), limit), dense.size(), lexical.size(), epoch, usedFallback, recalls.degraded());
    }

    private static List<String> topIds(List<Scored> scored, int limit) {
        return scored.stream().map(Scored::ruleId).limit(limit).toList();
    }

    private static List<String> topIdsFromFused(List<Retrieved> fused, int limit) {
        return fused.stream().map(Retrieved::ruleId).limit(limit).toList();
    }

    private record Recalls(List<Scored> dense, List<Scored> lexical, boolean degraded) {
    }

    private record Path(List<Scored> hits, boolean failed) {
    }

    public record Diagnosis(List<String> denseTop, List<String> lexicalTop, List<String> fusedTop,
                            int denseHits, int lexicalHits, long kbEpoch, boolean intentFilterRelaxed,
                            boolean degraded) {
    }

    private List<Scored> denseRecall(String query, List<String> visibleTenants, Intent intent, long epoch) {
        // 向量化失败不在此处吞：上层要靠异常区分“这路挂了”与“库里确实没有”
        float[] vector = embedding.embed(query);
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(Map.of("key", "kb_epoch", "match", Map.of("value", epoch)));
        if (intent != null) {
            must.add(Map.of("key", "intent", "match", Map.of("value", intent.name())));
        }
        // 可见范围 = 本店铺 + 平台，用 match.any 表达；Qdrant 的 should 需要配 min_should 结构体，
        // 写成 ES 风格的 min_should_match 会被 400 直接拒掉
        must.add(Map.of("key", "tenant_id", "match", Map.of("any", visibleTenants)));
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("must", must);
        List<QdrantRestClient.Hit> hits = qdrant.search(config.ruleCollection(), vector, filter, config.denseTopK(), null);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            // Qdrant 的 point id 是 ruleId 派生的 UUID，融合与引用必须用 payload 里的 rule_id
            Map<String, Object> payload = hits.get(i).payload();
            String ruleId = stringField(payload, "rule_id", hits.get(i).id());
            scored.add(new Scored(ruleId, i,
                    stringField(hits.get(i).payload(), "scope", "SHOP"),
                    stringField(payload, "title", ""),
                    stringField(payload, "text", "")));
        }
        return scored;
    }

    private List<Scored> lexicalRecall(String query, List<String> visibleTenants, Intent intent, long epoch) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("kbEpoch", epoch);
        if (intent != null) {
            filters.put("intent", intent.name());
        }
        // ES 的 terms 查询表达"本店铺 + 平台"两个可见范围
        List<EsRestClient.Hit> hits = esSearchWithTenants(query, visibleTenants, filters, config.lexicalTopK());
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            EsRestClient.Hit hit = hits.get(i);
            scored.add(new Scored(hit.ruleId(), i, hit.scope(), hit.title(), hit.text()));
        }
        return scored;
    }

    private List<EsRestClient.Hit> esSearchWithTenants(String query, List<String> visibleTenants,
                                                       Map<String, Object> filters, int size) {
        List<Map<String, Object>> must = List.of(Map.of("multi_match", Map.of(
                "query", query, "fields", List.of("text^2", "title"), "type", "best_fields")));
        List<Map<String, Object>> filter = new ArrayList<>();
        filter.add(Map.of("terms", Map.of("tenantId", visibleTenants)));
        filters.forEach((key, value) -> filter.add(Map.of("term", Map.of(key, value))));
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", must);
        bool.put("filter", filter);
        try {
            return es.searchRaw(config.esIndex(), Map.of("size", size,
                    "_source", List.of("ruleId", "text", "title", "ruleType", "applicableCategory", "scope"),
                    "query", Map.of("bool", bool)));
        } catch (RuntimeException failure) {
            // 异常抛到 recall() 那层统一记 degraded：绝不把“挂了”翻译成“没查到”
            throw new IllegalStateException("ES 词法召回失败", failure);
        }
    }

    /** Reciprocal Rank Fusion：两路都靠前的规则块胜出，避免单路刷分。 */
    private List<Retrieved> rrf(List<Scored> dense, List<Scored> lexical) {
        Map<String, Aggregated> merged = new LinkedHashMap<>();
        accumulate(merged, dense, true);
        accumulate(merged, lexical, false);
        return merged.values().stream()
                .sorted(Comparator.comparingDouble((Aggregated value) -> value.score).reversed())
                .map(Aggregated::toRetrieved)
                .toList();
    }

    private void accumulate(Map<String, Aggregated> merged, List<Scored> ranked, boolean isDense) {
        for (Scored item : ranked) {
            Aggregated aggregated = merged.computeIfAbsent(item.ruleId(), Aggregated::new);
            aggregated.score += 1.0d / (config.rrfK() + item.rank() + 1);
            if (aggregated.scope == null) {
                aggregated.scope = item.scope();
            }
            // 正文只有一份，两路召回谁先拿到算谁的；后到的一路若带回了正文也补上
            if (aggregated.text == null || aggregated.text.isBlank()) {
                aggregated.text = item.text();
                aggregated.title = item.title();
            }
            if (isDense) {
                aggregated.denseRank = item.rank() + 1;
            } else {
                aggregated.lexicalRank = item.rank() + 1;
            }
        }
    }

    private static <T> T timed(Timer timer, Supplier<T> action) {
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            timer.record(java.time.Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private record Scored(String ruleId, int rank, String scope, String title, String text) {
    }

    private static final class Aggregated {
        private final String ruleId;
        private String scope;
        private double score;
        private int denseRank;
        private int lexicalRank;
        private String title;
        private String text;

        private Aggregated(String ruleId) {
            this.ruleId = ruleId;
        }

        private Retrieved toRetrieved() {
            return new Retrieved(ruleId, score, denseRank, lexicalRank, scope, title, text);
        }
    }

    /** @param text 条款原文，注入 Prompt 用；检索侧拿不到正文就等于答不出，见 ADR 0010 */
    public record Retrieved(String ruleId, double rrfScore, int denseRank, int lexicalRank, String scope,
                            String title, String text) {
    }

    public record Result(List<Retrieved> rules, int denseHits, int lexicalHits, long kbEpoch, boolean degraded) {

        public Result(List<Retrieved> rules, int denseHits, int lexicalHits, long kbEpoch) {
            this(rules, denseHits, lexicalHits, kbEpoch, false);
        }

        /** 检索没跑成：与“跑完了、命中 0 条”严格区分。 */
        public static Result unavailable(long epoch) {
            return new Result(List.of(), 0, 0, epoch, true);
        }

        public boolean empty() {
            return rules.isEmpty();
        }
    }

    private static String stringField(Map<String, Object> payload, String key, String fallback) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
