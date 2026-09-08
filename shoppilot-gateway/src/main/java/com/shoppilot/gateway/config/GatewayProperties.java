package com.shoppilot.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 全量配置集中声明，避免各处 @Value 散落。 */
@ConfigurationProperties(prefix = "shoppilot")
public record GatewayProperties(
        Llm llm,
        Embedding embedding,
        Retrieval retrieval,
        Cache cache,
        BizMock bizmock,
        Agent agent,
        RateLimit ratelimit,
        Ingest ingest,
        Triage triage,
        Ops ops) {

    public record Llm(String mode, String baseUrl, String apiKey, String model, double temperature,
                      Duration connectTimeout, Duration readTimeout, long dailyTokenBudget,
                      String localBaseUrl, String localModel,
                      Duration perfFirstTokenLatency, Duration perfTotalLatency) {

        public boolean dev() {
            return "dev".equalsIgnoreCase(mode);
        }

        public boolean local() {
            return "local".equalsIgnoreCase(mode);
        }

        public boolean perf() {
            return "perf".equalsIgnoreCase(mode);
        }
    }

    public record Embedding(String baseUrl, String model, int dimension, Duration timeout,
                            Duration warmupTimeout) {
    }

    public record Retrieval(String qdrantUrl, String esUrl, String ruleCollection, String cacheCollection,
                            String esIndex, int denseTopK, int lexicalTopK, int fusedTopK, int rrfK) {
    }

    public record Cache(boolean enabled, Duration l1Ttl, double semanticThreshold, Duration negativeTtl,
                        Duration singleflightWaitTimeout) {
    }

    public record BizMock(String baseUrl, String internalToken, Duration connectTimeout, Duration readTimeout) {
    }

    public record Agent(int maxToolRounds, int maxSlotAsks, Duration sessionTtl, int historyTurns) {
    }

    /**
     * @param overrideTenantQuota true 时店铺配额取 {@code defaultTenantQps}，不再读 biz-mock 的 tenants 表。
     *                            只有压测 profile 该开：真实流量要的是每店配额，不是全局大数。
     */
    public record RateLimit(boolean enabled, long defaultTenantQps, long customerQps, long ipQps,
                            boolean overrideTenantQuota) {
    }

    /** 离线入库脚本的配置，只有 ingest profile 会用到。 */
    public record Ingest(String dir) {
    }

    public record Triage(String centroidCache) {
    }

    /**
     * 运维代理端点（ticket 14/15）。
     *
     * <p>浏览器只与同源网关通信，{@code X-Internal-Token} 永远由网关在服务端补上。
     * 故障注入与复位是平台级动作，额外要求一个运维凭证；生产把 {@code enabled=false} 整条关掉。
     */
    public record Ops(boolean enabled, String token) {
    }
}
