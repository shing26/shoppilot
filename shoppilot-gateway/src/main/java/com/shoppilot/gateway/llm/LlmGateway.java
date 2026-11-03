package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.PromptCatalog;
import com.shoppilot.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.nio.file.Path;
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
                      TokenBudget tokenBudget, MeterRegistry registry, LlmFaultInjector faultInjector,
                      PromptCatalog promptCatalog) {
        this.properties = properties;
        this.tokenBudget = tokenBudget;
        this.faultInjector = faultInjector;
        GatewayProperties.Llm llm = properties.llm();
        if (llm.replay()) {
            // 录放门禁：只读 cassette。缺卡 fail-closed，绝不回退真实模型或 Mock（ADR 0083）。
            String dir = llm.replayCassetteDir() == null || llm.replayCassetteDir().isBlank()
                    ? "eval/llm-cassettes" : llm.replayCassetteDir();
            String replayModel = llm.replayModel() == null || llm.replayModel().isBlank()
                    ? llm.model() : llm.replayModel();
            this.delegate = new ReplayLlmClient(mapper, Path.of(dir), replayModel,
                    promptCatalog.version(), llm.maxOutputTokens(), llm.temperature());
        } else {
            LlmClient base;
            if (llm.demo()) {
                base = new DemoLlmClient(llm, mapper);
            } else if (llm.perf()) {
                base = new MockLlmClient(llm);
            } else if (llm.local()) {
                base = new OllamaLlmClient(httpClient, mapper, llm);
            } else {
                base = new OpenAiCompatibleLlmClient(httpClient, mapper, llm);
            }
            // 录制只对真实模型生效：perf/demo 的确定性输出不能冒充模型证据。
            if ((llm.dev() || llm.local()) && llm.recordDir() != null && !llm.recordDir().isBlank()) {
                this.delegate = new RecordingLlmClient(base, mapper, Path.of(llm.recordDir()),
                        effectiveModel(llm), promptCatalog.version(), llm.mode(),
                        llm.maxOutputTokens(), llm.temperature(), false);
            } else {
                this.delegate = base;
            }
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
        // round22 票 66：再加一个 source 标签，把"这个数是估的还是供应商报的"写成机器可读的
        // —— 加标签不加指标名，所以指标名计数不变。
        this.tokenCounter = Counter.builder("shoppilot_llm_tokens_total")
                .tag("mode", delegate.mode()).tag("source", delegate.tokenSource()).register(registry);
    }

    /** 录制目录与 key 里的模型名必须同源：dev 用 {@code model}，local 用 Ollama 的模型名。 */
    private static String effectiveModel(GatewayProperties.Llm llm) {
        return llm.local() && llm.localModel() != null && !llm.localModel().isBlank()
                ? llm.localModel() : llm.model();
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
