package com.shoppilot.gateway.sentiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.PromptCatalog;
import com.shoppilot.gateway.llm.LlmException;
import com.shoppilot.gateway.llm.LlmGateway;
import com.shoppilot.gateway.llm.LlmTypes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 情绪门（ADR 0034）：插在 INTAKE → TRIAGE 之间，不让情绪激动的买家走完全链路再转人工。
 *
 * <p>两级判定：第一层情绪词典（纯 JVM、0 token、可配置），命中强愤怒/威胁/急迫词直接定案；
 * 第二层词典不确定时走一次 LLM 分类（复用 LlmGateway），LLM 不可用或解析失败一律
 * {@link Emotion#UNCERTAIN}——情绪门 fail-open，链路可靠性兜底仍由后续降级因子承担。
 *
 * <p>第二层只在 {@code dev} 口径启用（ADR 0043）。perf 的理由是票 36 登记的：MockLlmClient 刻意
 * "不聪明"，对它做情绪分类必然 UNCERTAIN，还会给含缓存命中在内的每个请求平添一跳固定延迟，
 * 压测读数全部失真。local 的理由是这条门**位于 INTAKE、先于缓存查询**——它一旦调模型，
 * "命中路径零模型调用"这条承诺项在 local 下就不成立（round17 活体验收登记 F1 实测
 * {@code sentiment_llm_classified_total=47 / requests_total=50}，即 94% 的请求各多一跳）。
 * local 是演示与 22 步活体验收的口径，dev 是唯一保留语义情绪判断的口径。
 */
@Component
public class SentimentGate {

    private static final Logger log = LoggerFactory.getLogger(SentimentGate.class);
    private static final String LEXICON = "sentiment/lexicon.yml";
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final LlmGateway llm;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;
    private final String classifierPrompt;
    private final String classifierVersion;
    private final List<String> strongAnger;
    private final List<String> threatMarkers;
    private final List<String> urgentMarkers;
    private final Counter lexiconDecidedCounter;
    private final Counter llmClassifiedCounter;
    private final Timer llmClassifyTimer;

    /** @param sentimentClassifierCatalog 分类器提示词的版本化资产（ADR 0037 纪律：外置、可归因、fail-fast） */
    public SentimentGate(LlmGateway llm, ObjectMapper mapper, MeterRegistry registry,
                         PromptCatalog sentimentClassifierCatalog) {
        this.llm = llm;
        this.mapper = mapper;
        this.registry = registry;
        this.classifierPrompt = sentimentClassifierCatalog.systemPrompt();
        this.classifierVersion = sentimentClassifierCatalog.version();
        log.info("情绪分类器提示词版本 {} 已加载", classifierVersion);
        Map<String, List<String>> lexicon = loadLexicon();
        this.strongAnger = lexicon.get("strong_anger");
        this.threatMarkers = lexicon.get("threat_markers");
        this.urgentMarkers = lexicon.get("urgent_markers");
        this.lexiconDecidedCounter = Counter.builder("shoppilot_sentiment_lexicon_decided_total").register(registry);
        this.llmClassifiedCounter = Counter.builder("shoppilot_sentiment_llm_classified_total").register(registry);
        this.llmClassifyTimer = Timer.builder("shoppilot_sentiment_llm_latency_seconds")
                .description("情绪分类 LLM 耗时").register(registry);
    }

    public Verdict evaluate(String query) {
        // ANGRY 命中优先于 URGENT：又急又气的用户按愤怒处理，话术先安抚
        if (containsAny(query, strongAnger) || containsAny(query, threatMarkers)) {
            lexiconDecidedCounter.increment();
            countEscalation(Emotion.ANGRY);
            return Verdict.lexicon(Emotion.ANGRY);
        }
        if (containsAny(query, urgentMarkers)) {
            lexiconDecidedCounter.increment();
            countEscalation(Emotion.URGENT);
            return Verdict.lexicon(Emotion.URGENT);
        }
        if (!"dev".equals(llm.mode())) {
            return Verdict.uncertain(llm.mode() + "-lexicon-only");
        }
        long started = System.nanoTime();
        try {
            LlmTypes.Reply reply = llm.complete(new LlmTypes.Request(List.of(
                    LlmTypes.Message.system(classifierPrompt),
                    LlmTypes.Message.user(query)), List.of(), 0.0d));
            Verdict verdict = fromClassification(reply);
            llmClassifyTimer.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            if (verdict.source().equals("llm")) {
                llmClassifiedCounter.increment();
                if (verdict.escalated()) {
                    countEscalation(verdict.emotion());
                }
            }
            return verdict;
        } catch (LlmException unavailable) {
            log.debug("情绪分类 LLM 不可用，fail-open: {}", unavailable.getMessage());
            return Verdict.uncertain("llm-unavailable");
        }
    }

    /** 升级计数按 emotion 分维度（ADR 0034 的三个新指标之一）。 */
    private void countEscalation(Emotion emotion) {
        registry.counter("shoppilot_sentiment_escalated_total", "emotion", emotion.name()).increment();
    }

    private Verdict fromClassification(LlmTypes.Reply reply) {
        if (reply == null || reply.content() == null || reply.content().isBlank()) {
            return Verdict.uncertainPaid("empty-classification", reply);
        }
        String content = reply.content();
        Matcher object = JSON_OBJECT.matcher(content);
        if (!object.find()) {
            return Verdict.uncertainPaid("unparsable-classification", reply);
        }
        try {
            JsonNode root = mapper.readTree(object.group());
            Emotion emotion;
            try {
                emotion = Emotion.valueOf(root.path("emotion").asText("").toUpperCase());
            } catch (IllegalArgumentException unknownLabel) {
                return Verdict.uncertainPaid("unknown-emotion-label", reply);
            }
            double confidence = root.path("confidence").asDouble(0.0d);
            boolean escalated = emotion.escalates(confidence);
            return new Verdict(emotion, confidence, escalated, "llm",
                    reply.promptTokens(), reply.completionTokens());
        } catch (IOException malformed) {
            return Verdict.uncertainPaid("unparsable-classification", reply);
        }
    }

    private boolean containsAny(String query, List<String> markers) {
        return markers != null && markers.stream().anyMatch(query::contains);
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> loadLexicon() {
        try (InputStream in = new ClassPathResource(LEXICON).getInputStream()) {
            Map<String, List<String>> lexicon = new Yaml().load(in);
            if (lexicon == null || lexicon.get("strong_anger") == null || lexicon.get("threat_markers") == null
                    || lexicon.get("urgent_markers") == null) {
                throw new IllegalStateException(LEXICON + " 缺少必需词表（strong_anger/threat_markers/urgent_markers）");
            }
            return lexicon;
        } catch (IOException unreadable) {
            throw new IllegalStateException(LEXICON + " 读取失败（词典是情绪门的必需资产）", unreadable);
        }
    }

    /**
     * 一次情绪判定的结论。{@code source} 说明判定来自哪一层：lexicon / llm / 各类 fail-open 形态，
     * 评测与验收脚本按这个词做 0 token 定案断言。
     *
     * <p>第二层真的调了模型时，两个 token 字段带回这次调用的用量（round35 票 145 / R12）：
     * 入口层这一跳连缓存命中的请求也要付钱，不记进响应字段的话「响应 token 求全部调用之和」
     * 这条口径对命中路径恒为 0。词典层与 fail-open 形态不调模型，照实记 0。
     */
    public record Verdict(Emotion emotion, double confidence, boolean escalated, String source,
                          int promptTokens, int completionTokens) {

        /** **调模型之前**就收口的形态（词典层定案 / 档位不启用第二层）：token 照实记 0。 */
        public Verdict(Emotion emotion, double confidence, boolean escalated, String source) {
            this(emotion, confidence, escalated, source, 0, 0);
        }

        static Verdict lexicon(Emotion emotion) {
            return new Verdict(emotion, 1.0d, true, "lexicon");
        }

        static Verdict uncertain(String source) {
            return new Verdict(Emotion.UNCERTAIN, 0.0d, false, source);
        }

        /**
         * **模型已经调过**、只是结论没法用（分类正文为空 / 不是 JSON / 标签不认识）的 fail-open：
         * 这一跳的钱照付，token 必须跟着结论走——否则缓存命中那类"没有别的模型调用"的请求会
         * 上报 0 而账上已经花掉（round35 票 145 / R12 要杀的就是这个形态）。
         */
        static Verdict uncertainPaid(String source, LlmTypes.Reply reply) {
            return new Verdict(Emotion.UNCERTAIN, 0.0d, false, source,
                    reply == null ? 0 : reply.promptTokens(), reply == null ? 0 : reply.completionTokens());
        }
    }
}
