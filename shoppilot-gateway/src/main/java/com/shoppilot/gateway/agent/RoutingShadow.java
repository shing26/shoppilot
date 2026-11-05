package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.tool.Intent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage3 按意图路由的影子观测（round48 补口 / ADR 0083）。
 *
 * <p><b>只观测，不执行</b>：按 {@code docs/STAGE3_MODEL_ROUTING.md} 的路由表算出「这一轮本该走哪个模型」，
 * 与进程当前实际使用的 {@code shoppilot.llm.mode} 比对后记一条计数。调用的模型、提示词、工具面
 * 一个字节都不变——所以它可以在默认配置下安全关闭，也不会把离线表的结论偷偷带进线上。
 *
 * <p>路由表（2026-10-27，180 gold）：ESCALATE 走确定性出口（无模型）；POLICY_* 与 UNKNOWN 走 local；
 * ACTION_* 走 dev。实际模式为 demo/perf/replay 时不算任何真实模型路由，一律记不一致。
 *
 * <p>默认关闭时 {@link #observe} 直接返回：不建计数器、不做意图映射，零开销。
 */
final class RoutingShadow {

    static final String METRIC = "shoppilot_routing_shadow_total";

    private final MeterRegistry registry;
    private final boolean enabled;
    private final String actual;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    RoutingShadow(boolean enabled, String actualMode, MeterRegistry registry) {
        this.enabled = enabled;
        this.actual = actualMode == null || actualMode.isBlank()
                ? "unknown" : actualMode.toLowerCase(Locale.ROOT);
        this.registry = registry;
    }

    static RoutingShadow from(GatewayProperties properties, MeterRegistry registry) {
        GatewayProperties.Routing routing = properties == null ? null : properties.routing();
        GatewayProperties.Llm llm = properties == null ? null : properties.llm();
        return new RoutingShadow(routing != null && routing.shadow(), llm == null ? null : llm.mode(), registry);
    }

    /** 每轮**模型路径**调用一次；缓存命中与转人工不经过这里，也不该被算进路由分布。 */
    void observe(Intent intent) {
        if (!enabled) {
            return;
        }
        String suggested = suggested(intent);
        String intentClass = intent == null ? "unknown" : intent.isAction() ? "action" : "policy";
        // ESCALATE 不需要模型；实际模式是什么都算一致，否则会把「本来就不该调模型」记成偏差。
        boolean agree = "none".equals(suggested) ? intent == Intent.ESCALATE : actual.equals(suggested);
        counters.computeIfAbsent(intentClass + "|" + suggested + "|" + agree, key -> Counter.builder(METRIC)
                        .description("Stage3 路由表建议与实际模式的一致情况（影子：不影响执行）")
                        .tags("intent_class", intentClass, "suggested", suggested,
                                "actual", actual, "agree", Boolean.toString(agree))
                        .register(registry))
                .increment();
    }

    private static String suggested(Intent intent) {
        if (intent == Intent.ESCALATE) {
            return "none";
        }
        return intent != null && intent.isAction() ? "dev" : "local";
    }
}
