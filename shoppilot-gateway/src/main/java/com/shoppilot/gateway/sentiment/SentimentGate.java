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
 * <p>perf 口径只有词典层：MockLlmClient 刻意"不聪明"，对它做情绪分类必然 UNCERTAIN，
 * 还会给含缓存命中在内的每个请求平添一跳固定延迟，压测读数全部失真（票 36 登记口径）。
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
        if ("perf".equals(llm.mode())) {
            return Verdict.uncertain("perf-lexicon-only");
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
            return Verdict.uncertain("empty-classification");
        }
        String content = reply.content();
        Matcher object = JSON_OBJECT.matcher(content);
        if (!object.find()) {
            return Verdict.uncertain("unparsable-classification");
        }
        try {
            JsonNode root = mapper.readTree(object.group());
            Emotion emotion;
            try {
                emotion = Emotion.valueOf(root.path("emotion").asText("").toUpperCase());
            } catch (IllegalArgumentException unknownLabel) {
                return Verdict.uncertain("unknown-emotion-label");
            }
            double confidence = root.path("confidence").asDouble(0.0d);
            boolean escalated = emotion.escalates(confidence);
            return new Verdict(emotion, confidence, escalated, "llm");
        } catch (IOException malformed) {
            return Verdict.uncertain("unparsable-classification");
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
     */
    public record Verdict(Emotion emotion, double confidence, boolean escalated, String source) {

        static Verdict lexicon(Emotion emotion) {
            return new Verdict(emotion, 1.0d, true, "lexicon");
        }

        static Verdict uncertain(String source) {
            return new Verdict(Emotion.UNCERTAIN, 0.0d, false, source);
        }
    }
}
