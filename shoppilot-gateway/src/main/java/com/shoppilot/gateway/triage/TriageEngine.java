package com.shoppilot.gateway.triage;

import com.shoppilot.gateway.knowledge.EmbeddingClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 三级级联判定，成本递增、能早停即早停（ADR 0007）。
 *
 * <p>T0 定案时完全不碰 embedding；只有 T0 拿不准才向量化，且这次向量化会被
 * L2 语义缓存直接复用——不为分类多花一次算力是本设计的关键约束。
 */
@Component
public class TriageEngine {

    private final T0RuleLayer t0;
    private final T1CentroidLayer t1;
    private final EmbeddingClient embedding;
    private final Counter earlyExitCounter;
    private final Counter escalatedToModelCounter;

    public TriageEngine(T0RuleLayer t0, T1CentroidLayer t1, EmbeddingClient embedding, MeterRegistry registry) {
        this.t0 = t0;
        this.t1 = t1;
        this.embedding = embedding;
        this.earlyExitCounter = Counter.builder("shoppilot_triage_early_exit_total")
                .description("T0 或 T1 定案、未进模型的请求数").register(registry);
        this.escalatedToModelCounter = Counter.builder("shoppilot_triage_to_model_total")
                .description("判定不确定、交由模型带 tools 定案的请求数").register(registry);
    }

    /**
     * @param queryVector T0 定案时为 null；否则为已算好的向量，供下游缓存复用
     */
    public record Outcome(TriageResult result, float[] queryVector) {
    }

    public Outcome triage(String query) {
        Optional<TriageResult> byRules = t0.classify(query);
        if (byRules.isPresent()) {
            earlyExitCounter.increment();
            return new Outcome(byRules.get(), null);
        }
        // 向量化失败不阻断请求：判定降级为"不确定"，缓存自然不准入，交给带 tools 的模型定案
        float[] embedded;
        try {
            embedded = embedding.embed(query);
        } catch (RuntimeException embeddingUnavailable) {
            escalatedToModelCounter.increment();
            return new Outcome(TriageResult.undecided(), null);
        }
        float[] vector = embedded;
        Optional<TriageResult> byCentroid = t1.classify(query, vector);
        if (byCentroid.isPresent()) {
            earlyExitCounter.increment();
            return new Outcome(byCentroid.get(), vector);
        }
        // 两级都拿不准：fail-closed，本轮不进缓存，交给带 tools 的模型定案
        escalatedToModelCounter.increment();
        return new Outcome(TriageResult.undecided(), vector);
    }
}
