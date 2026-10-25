package com.shoppilot.gateway.quality;

import com.shoppilot.tool.audit.AuditTopics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读编排质量 sidecar（ADR 0072 H4）。
 *
 * <p>它只读 Micrometer 指标并归并报表：不改路由、不补 LLM 调用、不判定某张单该怎么处理。
 * 计数缺失按 0 报出，因为“还没有发生”是合法读数；不猜历史，也不造第二份状态。
 */
@Component
public class QualitySidecarService {

    private static final int AUDIT_SCAN_COUNT = 1000;
    private static final int AUDIT_WINDOW_MINUTES = 15;
    private static final int AUDIT_CACHE_TTL_SECONDS = 30;

    private final MeterRegistry registry;
    private final StringRedisTemplate redis;

    /** 报表是人工排查面，不是高频数据源；audit 窗口 30s 内复用同一份读数，避免轮询打 Redis。 */
    private volatile Instant auditCachedAt;
    private volatile Map<String, Object> auditCache;

    public QualitySidecarService(MeterRegistry registry, StringRedisTemplate redis) {
        this.registry = registry;
        this.redis = redis;
    }

    public Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("scope", "process-local-cumulative");

        double requests = sum("shoppilot_requests_total");
        double fallback = sum("shoppilot_fallback_total");
        Map<String, Object> requestsReport = new LinkedHashMap<>();
        requestsReport.put("total", requests);
        requestsReport.put("fallbacks", fallback);
        requestsReport.put("fallbackRatio", ratio(fallback, requests));
        report.put("requests", requestsReport);

        List<Map<String, Object>> handoffRows = rows("shoppilot_handoff_total", "from", "to", "result");
        double allowed = sumRows(handoffRows, "result", "ALLOWED");
        double blocked = sumRows(handoffRows, "result", "BLOCKED");
        Map<String, Object> handoffReport = new LinkedHashMap<>();
        handoffReport.put("rows", handoffRows);
        handoffReport.put("allowed", allowed);
        handoffReport.put("blocked", blocked);
        handoffReport.put("blockedRatio", ratio(blocked, allowed + blocked));
        handoffReport.put("blockedByReason", blockedByReason());
        handoffReport.put("decisionSeconds", timer("shoppilot_handoff_decision_seconds"));
        report.put("handoff", handoffReport);

        Map<String, Object> toolRiskReport = new LinkedHashMap<>();
        toolRiskReport.put("rows", rows("shoppilot_tool_risk_attempts_total", "tool", "risk", "hitl", "role"));
        report.put("toolRisk", toolRiskReport);

        Map<String, Object> friction = new LinkedHashMap<>();
        friction.put("toolSuccessFallbacks", sum("shoppilot_quality_tool_success_fallback_total"));
        friction.put("repeatedSlotAsks", sum("shoppilot_quality_slot_asks_repeated_total"));
        friction.put("repeatedSlotAsksByPhase",
                rows("shoppilot_quality_slot_asks_repeated_total", "phase", "sameSlot"));
        friction.put("handoffContextLosses", rows("shoppilot_handoff_context_loss_total", "slot"));
        report.put("friction", friction);
        report.put("audit", auditWindow());
        return report;
    }

    /**
     * Redis audit 流的 15 分钟窗口汇总。审计只记录坐席/资金等留痕动作，因此它是
     * “工单动作面”的时间窗读数；Handoff / tool risk 的时间窗由 Prometheus rate/increase 查询。
     */
    private Map<String, Object> auditWindow() {
        Instant cachedAt = auditCachedAt;
        Map<String, Object> cached = auditCache;
        if (cached != null && cachedAt != null
                && cachedAt.isAfter(Instant.now().minusSeconds(AUDIT_CACHE_TTL_SECONDS))) {
            return cached;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("source", "redis-stream:" + AuditTopics.AUDIT);
        view.put("windowMinutes", AUDIT_WINDOW_MINUTES);
        Map<String, Double> actions = new LinkedHashMap<>();
        if (redis == null) {
            view.put("available", false);
            view.put("actions", actions);
            return view;
        }
        try {
            Instant cutoff = Instant.now().minusSeconds(AUDIT_WINDOW_MINUTES * 60L);
            var records = redis.opsForStream().reverseRange(AuditTopics.AUDIT, Range.unbounded(),
                    Limit.limit().count(AUDIT_SCAN_COUNT));
            int scanned = 0;
            if (records != null) {
                for (MapRecord<String, Object, Object> record : records) {
                    scanned++;
                    Map<Object, Object> fields = record.getValue();
                    Instant occurred = Instant.parse(String.valueOf(fields.get("occurredAt")));
                    if (occurred.isBefore(cutoff)) {
                        continue;
                    }
                    actions.merge(String.valueOf(fields.get("action")), 1d, Double::sum);
                }
            }
            view.put("available", true);
            view.put("eventsScanned", scanned);
            view.put("actions", actions);
        } catch (RuntimeException redisUnavailable) {
            view.put("available", false);
            view.put("actions", actions);
        }
        auditCache = view;
        auditCachedAt = Instant.now();
        return view;
    }

    /** blocked 的 reason 分布：键是 from->to|reason，值是次数；sidecar 直接可读，不需要再二次归并。 */
    private Map<String, Object> blockedByReason() {
        Map<String, Double> distribution = new LinkedHashMap<>();
        registry.find("shoppilot_handoff_blocked_total").counters().forEach(counter -> {
            String key = counter.getId().getTag("from") + "->" + counter.getId().getTag("to")
                    + "|" + counter.getId().getTag("reason");
            distribution.merge(key, counter.count(), Double::sum);
        });
        return new LinkedHashMap<String, Object>(distribution);
    }

    private List<Map<String, Object>> counters(String name, String... tagKeys) {
        List<Map<String, Object>> rows = new ArrayList<>();
        registry.find(name).counters().forEach(counter -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            for (String key : tagKeys) {
                row.put(key, counter.getId().getTag(key));
            }
            row.put("count", counter.count());
            rows.add(row);
        });
        return rows;
    }

    private List<Map<String, Object>> rows(String name, String... tagKeys) {
        return counters(name, tagKeys);
    }

    private double sum(String name) {
        return registry.find(name).counters().stream().mapToDouble(counter -> counter.count()).sum();
    }

    private double sumRows(List<Map<String, Object>> rows, String tagKey, String tagValue) {
        return rows.stream()
                .filter(row -> tagValue.equals(String.valueOf(row.get(tagKey))))
                .mapToDouble(row -> ((Number) row.get("count")).doubleValue())
                .sum();
    }

    private Map<String, Object> timer(String name) {
        Timer timer = registry.find(name).timer();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("count", timer == null ? 0d : timer.count());
        view.put("meanSeconds", timer == null || timer.count() == 0 ? 0d : timer.mean(java.util.concurrent.TimeUnit.SECONDS));
        view.put("maxSeconds", timer == null ? 0d : timer.max(java.util.concurrent.TimeUnit.SECONDS));
        return view;
    }

    private static double ratio(double numerator, double denominator) {
        return denominator <= 0 ? 0d : numerator / denominator;
    }
}
