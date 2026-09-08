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
    private final LlmFaultInjector faultInjector;
    private final Counter failureCounter;
    private final Timer latencyTimer;
    private final Counter completeCounter;
    private final Counter streamCounter;
    private final Counter tokenCounter;

    public LlmGateway(HttpClient httpClient, ObjectMapper mapper, GatewayProperties properties,
                      TokenBudget tokenBudget, MeterRegistry registry, LlmFaultInjector faultInjector) {
        this.properties = properties;
        this.tokenBudget = tokenBudget;
        this.faultInjector = faultInjector;
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
        // 命中路径必须"零模型调用"，这条断言只能靠计数器，不能靠读代码保证（PLAN 承诺项）
        this.completeCounter = Counter.builder("shoppilot_llm_calls_total")
                .tag("mode", delegate.mode()).tag("kind", "complete").register(registry);
        this.streamCounter = Counter.builder("shoppilot_llm_calls_total")
                .tag("mode", delegate.mode()).tag("kind", "stream").register(registry);
        // Token 节约率要能在 perf 模式下测：日预算只在 dev 记账，压测里读不到，
        // 所以单独放一个"实际发给模型的 token 数"计数器，缓存开/关两组直接比差值。
        this.tokenCounter = Counter.builder("shoppilot_llm_tokens_total")
                .tag("mode", delegate.mode()).register(registry);
    }

    public String mode() {
        return delegate.mode();
    }

    public LlmTypes.Reply complete(LlmTypes.Request request) {
        guardBudget();
        injectFault();
        completeCounter.increment();
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
        injectFault();
        streamCounter.increment();
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

    /** 注入的模型故障走的是与真实故障完全相同的异常类型，不是另开一条降级分支。 */
    private void injectFault() {
        if (properties.ops().enabled()) {
            faultInjector.checkOrThrow();
        }
    }

    private void record(LlmTypes.Reply reply) {
        tokenCounter.increment(reply.totalTokens());
        if (properties.llm().dev()) {
            tokenBudget.record(reply.totalTokens());
        }
    }
}
