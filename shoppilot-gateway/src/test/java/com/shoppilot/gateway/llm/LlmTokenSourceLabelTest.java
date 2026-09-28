package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * round22 票 66 / ADR 0048：`shoppilot_llm_tokens_total` 的**口径声明**要机器可读。
 *
 * <p>三个来源的计量方法不同：`MockLlmClient` 按字符数**估**，云端客户端用供应商回报的
 * `usage.prompt_tokens`，Ollama 用模型自报的 `prompt_eval_count`。加的是**标签**不是新指标名 ——
 * 所以指标名计数不变（本用例同时钉住这一条）。
 */
class LlmTokenSourceLabelTest {

    private static GatewayProperties.Llm llm(String mode) {
        return new GatewayProperties.Llm(mode, "http://127.0.0.1:1", "unused", "some-model", 0.0d,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0L, "http://127.0.0.1:11434", "qwen2.5:3b",
                Duration.ofMillis(300), Duration.ofMillis(500), 1024);
    }

    private static MeterRegistry gatewayFor(String mode) {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.llm()).thenReturn(llm(mode));
        MeterRegistry registry = new SimpleMeterRegistry();
        new LlmGateway(HttpClient.newHttpClient(), new ObjectMapper(), properties,
                mock(TokenBudget.class), registry, mock(LlmFaultInjector.class));
        return registry;
    }

    private static String sourceTagOf(MeterRegistry registry) {
        List<Meter> meters = registry.getMeters().stream()
                .filter(meter -> "shoppilot_llm_tokens_total".equals(meter.getId().getName())).toList();
        assertThat(meters).as("token 计数器必须恰好一条（同一个指标名，只加标签）").hasSize(1);
        return meters.get(0).getId().getTag("source");
    }

    @Test
    @DisplayName("perf 档（Mock）→ source=estimate")
    void mockModeIsLabelledAsEstimate() {
        assertThat(sourceTagOf(gatewayFor("perf"))).isEqualTo("estimate");
    }

    @Test
    @DisplayName("dev 档（云端）与 local 档（Ollama）→ source=provider")
    void providerModesAreLabelledAsProvider() {
        assertThat(sourceTagOf(gatewayFor("dev"))).isEqualTo("provider");
        assertThat(sourceTagOf(gatewayFor("local"))).isEqualTo("provider");
    }

    @Test
    @DisplayName("标签不新增指标名：仍是 shoppilot_llm_tokens_total 这一个名字")
    void labelDoesNotIntroduceANewMetricName() {
        MeterRegistry registry = gatewayFor("perf");
        long tokenNamed = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("shoppilot_llm_tokens")).count();
        assertThat(tokenNamed).as("加标签不许带出第二个 token 指标名").isEqualTo(1);
        // 计数照旧能读到（加标签不该把读数弄丢）
        Counter counter = registry.get("shoppilot_llm_tokens_total").tag("mode", "perf")
                .tag("source", "estimate").counter();
        counter.increment(7);
        assertThat(counter.count()).isEqualTo(7.0d);
    }
}
