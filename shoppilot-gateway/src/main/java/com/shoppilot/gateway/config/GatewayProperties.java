package com.shoppilot.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 全量配置集中声明，避免各处 @Value 散落。 */
@Validated
@ConfigurationProperties(prefix = "shoppilot")
public record GatewayProperties(
        @Valid Llm llm,
        @Valid Embedding embedding,
        @Valid Retrieval retrieval,
        @Valid Cache cache,
        @Valid BizMock bizmock,
        @Valid Agent agent,
        @Valid RateLimit ratelimit,
        @Valid Ingest ingest,
        @Valid Triage triage,
        @Valid Ops ops) {

    public record Llm(String mode, String baseUrl, String apiKey, String model,
                      @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
                      @DurationMin(nanos = 1, message = "shoppilot.llm.connect-timeout must be greater than 0")
                      Duration connectTimeout,
                      @DurationMin(nanos = 1, message = "shoppilot.llm.read-timeout must be greater than 0")
                      Duration readTimeout,
                      long dailyTokenBudget,
                      String localBaseUrl, String localModel,
                      @DurationMin(nanos = 1,
                              message = "shoppilot.llm.perf-first-token-latency must be greater than 0")
                      Duration perfFirstTokenLatency,
                      @DurationMin(nanos = 1,
                              message = "shoppilot.llm.perf-total-latency must be greater than 0")
                      Duration perfTotalLatency) {

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

    /**
     * @param inProcessCache 进程内向量缓存开关，默认开。
     *                       {@code no-embedding-cache} profile 关掉它，用来把
     *                       "L2 曲线的天花板是编排层还是本地 bge-m3 推理"这件事分开归因。
     */
    public record Embedding(
            String baseUrl,
            String model,
            int dimension,
            @DurationMin(nanos = 1, message = "shoppilot.embedding.timeout must be greater than 0")
            Duration timeout,
            @DurationMin(nanos = 1, message = "shoppilot.embedding.warmup-timeout must be greater than 0")
            Duration warmupTimeout,
            boolean inProcessCache) {
    }

    public record Retrieval(String qdrantUrl, String esUrl, String ruleCollection, String cacheCollection,
                            String esIndex, int denseTopK, int lexicalTopK, int fusedTopK, int rrfK) {
    }

    /**
     * @param singleflightEnabled 防击穿合并开关。Token 节约率的基线组要把它和缓存一起关掉，
     *                            否则"基线"里仍然有合并替模型省调用，测出来的节约率是假的。
     */
    public record Cache(
            boolean enabled,
            @DurationMin(nanos = 1, message = "shoppilot.cache.l1-ttl must be greater than 0")
            Duration l1Ttl,
            @DecimalMin("0.0") @DecimalMax("1.0") double semanticThreshold,
            @DurationMin(nanos = 1, message = "shoppilot.cache.negative-ttl must be greater than 0")
            Duration negativeTtl,
            @DurationMin(nanos = 1,
                    message = "shoppilot.cache.singleflight-wait-timeout must be greater than 0")
            Duration singleflightWaitTimeout,
            boolean singleflightEnabled) {
    }

    public record BizMock(
            String baseUrl,
            String internalToken,
            @DurationMin(nanos = 1, message = "shoppilot.bizmock.connect-timeout must be greater than 0")
            Duration connectTimeout,
            @DurationMin(nanos = 1, message = "shoppilot.bizmock.read-timeout must be greater than 0")
            Duration readTimeout) {
    }

    public record Agent(
            @Min(1) int maxToolRounds,
            int maxSlotAsks,
            @DurationMin(nanos = 1, message = "shoppilot.agent.session-ttl must be greater than 0")
            Duration sessionTtl,
            int historyTurns) {
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
