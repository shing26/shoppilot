package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.quality.QualitySidecarService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusNamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * round50 票 218 回归：单指标查询正常、整注册表抓取却 500 的缺陷。
 *
 * <p>生产上 {@code GET /actuator/prometheus} 抛
 * {@code IllegalStateException: shoppilot_handoff_decision_seconds_max: duplicate metric name}：
 * Timer 自动导出 {@code _seconds_max}，累计最大 Gauge 又用了同一个名字。
 * 这些用例用**真实的 {@link PrometheusMeterRegistry}**（不是 mock）走生产同一个注册方法，
 * 再调用 {@code scrape()}——也就是 actuator 端点真正走的导出路径。
 */
class PrometheusScrapeRegressionTest {

    private final PrometheusMeterRegistry registry =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @Test
    @DisplayName("整注册表 scrape 不再因 handoff 判定耗时指标重名而失败（round50 票 218）")
    void scrapesWholeRegistryWithHandoffDecisionMeters() {
        TurnServices.registerHandoffDecisionMeters(registry, new AtomicLong(1_000_000L));

        assertThatCode(registry::scrape).doesNotThrowAnyException();

        String exposition = registry.scrape();
        assertThat(exposition)
                .contains("shoppilot_handoff_decision_seconds_count")
                .contains("shoppilot_handoff_decision_max_seconds");
        assertThat(exposition).doesNotContain("duplicate metric name");
    }

    @Test
    @DisplayName("所有 meter 的 Prometheus 导出名唯一——同类重名问题防复发")
    void everyPrometheusExportNameIsUnique() {
        TurnServices.registerHandoffDecisionMeters(registry, new AtomicLong(0L));
        registry.counter("shoppilot_regression_counter_total").increment();
        Gauge.builder("shoppilot_regression_gauge", new AtomicLong(1L), AtomicLong::doubleValue)
                .register(registry);

        Map<String, Meter> owners = new HashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            for (String exportedName : prometheusExportNames(meter)) {
                Meter previous = owners.putIfAbsent(exportedName, meter);
                if (previous != null) {
                    duplicates.add(exportedName + " <- " + previous.getId() + " / " + meter.getId());
                }
            }
        }

        assertThat(duplicates)
                .as("Prometheus 导出名重复会让整次 scrape 失败，不能只靠单指标端点验证")
                .isEmpty();
    }

    @Test
    @DisplayName("sidecar 读累计最大的显式常量与生产注册名一致，改名不会两边漂移")
    void qualitySidecarReadsSameGaugeNameAsProduction() {
        assertThat(QualitySidecarService.HANDOFF_DECISION_MAX_GAUGE)
                .isEqualTo(TurnServices.HANDOFF_DECISION_MAX_GAUGE_NAME);
    }

    /**
     * 按 Prometheus 导出规则展开一个 meter 会占用的指标名。
     *
     * <p>Timer / distribution summary 在 exposition 里会被拆成 {@code _count / _sum / _max / _bucket}；
     * 其余类型直接用注册名（counter 的 {@code _total} 由命名约定补齐）。
     * 只需覆盖本仓库实际用到的类型，过度展开只会更严格，不会漏掉重名。
     */
    private static List<String> prometheusExportNames(Meter meter) {
        Meter.Id id = meter.getId();
        String name = new PrometheusNamingConvention().name(id.getName(), id.getType(), id.getBaseUnit());
        return switch (id.getType()) {
            case TIMER, DISTRIBUTION_SUMMARY -> List.of(
                    name + "_count", name + "_sum", name + "_max", name + "_bucket");
            default -> List.of(name);
        };
    }
}
