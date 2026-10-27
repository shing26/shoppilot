package com.shoppilot.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/** 全量配置集中声明，避免各处 @Value 散落。 */
@Validated
@ConfigurationProperties(prefix = "shoppilot")
public record GatewayProperties(
        @Valid Llm llm,
        @Valid Embedding embedding,
        @Valid Retrieval retrieval,
        @Valid Cache cache,
        @Valid Ticket ticket,
        @Valid BizMock bizmock,
        @Valid Agent agent,
        @Valid RateLimit ratelimit,
        @Valid Ingest ingest,
        @Valid Triage triage,
        @Valid TenantDomains tenantDomains,
        @Valid Ops ops,
        @Valid Feishu feishu) {

    public record Llm(
            // 未知/空模式会落进 LlmGateway 的 else（云端客户端），而 dev() 为假意味着
            // ADR 0012 的日预算被静默绕过——值域必须在绑定层拒绝。
            @Pattern(regexp = "(?i)^(dev|local|perf|demo)$",
                    message = "shoppilot.llm.mode must be one of dev|local|perf|demo") String mode,
            String baseUrl, String apiKey, String model,
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
                      Duration perfTotalLatency,
                      @Min(value = 0, message = "shoppilot.llm.max-output-tokens must not be negative（0 = 不限制）")
                      int maxOutputTokens) {

        public boolean dev() {
            return "dev".equalsIgnoreCase(mode);
        }

        public boolean local() {
            return "local".equalsIgnoreCase(mode);
        }

        public boolean perf() {
            return "perf".equalsIgnoreCase(mode);
        }

        /** 演示档：意图感知的确定性答案，质量与延迟都不冒充 dev/local。 */
        public boolean demo() {
            return "demo".equalsIgnoreCase(mode);
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

    /**
     * 工单与坐席服务（round23 票 72 / ADR 0053）。与 {@link BizMock} 同形：
     * 固定服务名 + 内部凭证 + 收发超时，不需要服务发现。
     */
    public record Ticket(
            String baseUrl,
            String internalToken,
            @DurationMin(nanos = 1, message = "shoppilot.ticket.connect-timeout must be greater than 0")
            Duration connectTimeout,
            @DurationMin(nanos = 1, message = "shoppilot.ticket.read-timeout must be greater than 0")
            Duration readTimeout) {
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
     * 租户行业登记（round33 / ADR 0067）。
     *
     * <p>未登记租户默认 UNKNOWN，只看本租户规则；平台级政策必须显式匹配 domain 才可见。
     */
    public record TenantDomains(Map<String, String> domains) {
    }

    /**
     * 运维代理端点（ticket 14/15）。
     *
     * <p>浏览器只与同源网关通信，{@code X-Internal-Token} 永远由网关在服务端补上。
     * 故障注入与复位是平台级动作，额外要求一个运维凭证；生产把 {@code enabled=false} 整条关掉。
     */
    public record Ops(boolean enabled, String token) {
    }

    /**
     * 飞书长连接配置（round31 票 113 / ADR 0065）。
     *
     * <p>凭据家法（PostureGuard 集成）在票 114 落地；本票先用占位配置打通解析层。
     * {@code enabled=true} 且凭据非空时长连接客户端才会注册到 SDK。
     */
    public record Feishu(boolean enabled, String appId, String appSecret, String tenant, String customer) {

        /** L1 单租户默认身份；飞书无 JWT，必须由配置注入，不能写死 "feishu"。 */
        public Feishu(boolean enabled, String appId, String appSecret, String tenant, String customer) {
            this.enabled = enabled;
            this.appId = appId;
            this.appSecret = appSecret;
            this.tenant = tenant == null || tenant.isBlank() ? "T001" : tenant.trim();
            this.customer = customer == null || customer.isBlank() ? "C001" : customer.trim();
        }
    }
}
