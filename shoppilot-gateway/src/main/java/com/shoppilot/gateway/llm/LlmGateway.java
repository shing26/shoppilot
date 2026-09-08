package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 按 profile 选择 LLM 客户端，并统一记账与埋点（ADR 0001）。
 *
 * <p>指标分口径的前提就在这里：perf 模式下的吞吐数字与 dev 模式下的质量数字
 * 来自不同客户端，绝不能混在一张表里对外宣称。
 */
@Component
public class LlmGateway {

    private final LlmClient delegate;
    private final TokenBudget tokenBudget;
    private final GatewayProperties properties;
    private final Counter failureCounter;
    private final Timer latencyTimer;

    public LlmGateway(HttpClient httpClient, ObjectMapper mapper, GatewayProperties properties,
                      TokenBudget tokenBudget, MeterRegistry registry) {
        this.properties = properties;
        this.tokenBudget = tokenBudget;
        GatewayProperties.Llm llm = properties.llm();
        if (llm.perf()) {
            this.delegate = new MockLlmClient(llm);
        } else if (llm.local()) {
            this.delegate = new OllamaLlmClient(httpClient, mapper, llm);
        } else {
            this.delegate = new OpenAiCompatibleLlmClient(httpClient, mapper, llm);
        }
        this.failureCounter = Counter.builder("shoppilot_llm_failure_total")
                .tag("mode", delegate.mode())
                .register(registry);
        this.latencyTimer = Timer.builder("shoppilot_llm_latency_seconds")
                .tag("mode", delegate.mode())
                .register(registry);
    }

    public String mode() {
        return delegate.mode();
    }

    public LlmTypes.Reply complete(LlmTypes.Request request) {
        guardBudget();
        long started = System.nanoTime();
        try {
            LlmTypes.Reply reply = delegate.complete(request);
            record(reply);
            return reply;
        } catch (LlmException failure) {
            failureCounter.increment();
            throw failure;
        } finally {
            latencyTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    public LlmTypes.Reply stream(LlmTypes.Request request, Consumer<String> tokenSink) {
        guardBudget();
        long started = System.nanoTime();
        try {
            LlmTypes.Reply reply = delegate.stream(request, tokenSink);
            record(reply);
            return reply;
        } catch (LlmException failure) {
            failureCounter.increment();
            throw failure;
        } finally {
            latencyTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void guardBudget() {
        if (properties.llm().dev()) {
            tokenBudget.checkOrThrow(properties.llm().dailyTokenBudget());
        }
    }

    private void record(LlmTypes.Reply reply) {
        if (properties.llm().dev()) {
            tokenBudget.record(reply.totalTokens());
        }
    }
}
