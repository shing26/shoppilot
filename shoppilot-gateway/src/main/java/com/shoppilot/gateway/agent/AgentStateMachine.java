package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.cache.SingleFlight;
import com.shoppilot.gateway.cache.WriteBackPolicy;
import com.shoppilot.gateway.cache.WriteBackPool;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.config.TenantDomainRegistry;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.llm.LlmException;
import com.shoppilot.gateway.llm.LlmGateway;
import com.shoppilot.gateway.sentiment.SentimentGate;
import com.shoppilot.gateway.store.StoreProfileProperties;
import com.shoppilot.gateway.store.StoreProfileService;
import com.shoppilot.gateway.style.StyleService;
import com.shoppilot.gateway.triage.TriageEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 有界 Agent 状态机（ADR 0008）的装配根。
 *
 * <p>round48 票 203 起，编排与执行面分别落在 {@link TurnOrchestrator} 与四个 handler；本类只保留
 * Spring bean 的身份、两套既有构造签名与唯一公开入口 {@link #run}，行为、状态、事件与指标名都不变。
 * 依赖仍从构造注入，测试照旧直接 {@code new AgentStateMachine(...)}。
 */
@Component
public class AgentStateMachine {

    private final TurnOrchestrator orchestrator;

    /**
     * 无店铺人格的构造重载：只给既有单测用（它们不关心提示词里的店铺段）。
     * 生产路径一律走下面带 {@link StoreProfileService} 的那一支。
     */
    public AgentStateMachine(TriageEngine triageEngine, CacheService cacheService, SingleFlight singleFlight,
                      WriteBackPolicy writeBackPolicy, KbEpoch kbEpoch, TenantDomainRegistry domainRegistry,
                      HybridRetriever retriever, LlmGateway llm, ToolDispatcher dispatcher,
                      SessionStore sessionStore, FallbackService fallbackService, GatewayProperties properties,
                      WriteBackPool writeBackPool, PromptCatalog promptCatalog, SentimentGate sentimentGate,
                      StyleService styleService, MeterRegistry registry) {
        this(triageEngine, cacheService, singleFlight, writeBackPolicy, kbEpoch, domainRegistry, retriever, llm,
                dispatcher, sessionStore, fallbackService, properties, writeBackPool, promptCatalog, sentimentGate,
                styleService, new StoreProfileService(new StoreProfileProperties(Map.of())), registry);
    }

    @Autowired
    public AgentStateMachine(TriageEngine triageEngine, CacheService cacheService, SingleFlight singleFlight,
                             WriteBackPolicy writeBackPolicy, KbEpoch kbEpoch, TenantDomainRegistry domainRegistry,
                             HybridRetriever retriever,
                             LlmGateway llm, ToolDispatcher dispatcher, SessionStore sessionStore,
                             FallbackService fallbackService, GatewayProperties properties,
                             WriteBackPool writeBackPool, PromptCatalog promptCatalog, SentimentGate sentimentGate,
                             StyleService styleService, StoreProfileService storeProfileService,
                             MeterRegistry registry) {
        this.orchestrator = new TurnOrchestrator(new TurnServices(triageEngine, cacheService, singleFlight,
                writeBackPolicy, kbEpoch, domainRegistry, retriever, llm, dispatcher, sessionStore, fallbackService,
                properties, writeBackPool, promptCatalog, sentimentGate, styleService, storeProfileService, registry));
    }

    public AgentResult run(String query, String idempotencyToken, EventSink sink) {
        return orchestrator.run(query, idempotencyToken, sink);
    }

    /** 包级可见只为让降级映射进用例（ticket 14）；三类模型失败必须各自映射到不同 reason。 */
    static FallbackReason mapLlmFailure(LlmException failure) {
        return TurnServices.mapLlmFailure(failure);
    }
}
