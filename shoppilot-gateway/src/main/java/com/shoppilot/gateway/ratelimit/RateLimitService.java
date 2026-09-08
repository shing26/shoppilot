package com.shoppilot.gateway.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双层令牌桶：店铺配额 + 买家/IP 防刷（ticket 13）。
 *
 * <p>两个维度各自独立生效：店铺配额保护的是"这家店别把平台算力吃干"，
 * 买家/IP 维度保护的是"单个爬虫别把网关连接占满"。只留一个都会漏。
 *
 * <p>限流判定必须发生在检索与模型调用之前，否则成本已经花出去了，限流只剩排队意义。
 */
@Component
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final Duration TENANT_QUOTA_TTL = Duration.ofSeconds(60);

    private final RedissonClient redisson;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final GatewayProperties.RateLimit config;
    private final MeterRegistry registry;
    private final String bizMockBase;
    private final String internalToken;
    private final Map<String, CachedQuota> quotas = new ConcurrentHashMap<>();
    private final Map<String, RRateLimiter> limiters = new ConcurrentHashMap<>();
    private final Map<String, Counter> rejections = new ConcurrentHashMap<>();

    public RateLimitService(RedissonClient redisson, HttpClient http, ObjectMapper mapper,
                            GatewayProperties properties, MeterRegistry registry) {
        this.redisson = redisson;
        this.http = http;
        this.mapper = mapper;
        this.config = properties.ratelimit();
        this.registry = registry;
        this.bizMockBase = properties.bizmock().baseUrl();
        this.internalToken = properties.bizmock().internalToken();
    }

    private Counter rejected(String dimension) {
        return rejections.computeIfAbsent(dimension, name -> Counter.builder("shoppilot_rate_limited_total")
                .description("按维度统计的被限流请求数，压测报告用它区分被限流与失败")
                .tag("dimension", name).register(registry));
    }

    /**
     * @param allowed false 时 {@code retryAfterMs} 给出建议退避时间
     * @param dimension 命中的维度，供指标与 SSE 文案使用
     */
    public record Decision(boolean allowed, long retryAfterMs, String dimension) {

        public static Decision pass() {
            return new Decision(true, 0, null);
        }
    }

    public Decision tryAcquire(String tenantId, String customerId, String ip) {
        if (!config.enabled()) {
            return Decision.pass();
        }
        Decision tenant = acquire("tenant:" + tenantId, tenantQuota(tenantId), "tenant");
        if (!tenant.allowed()) {
            return tenant;
        }
        Decision customer = acquire("customer:" + tenantId + ":" + customerId, config.customerQps(), "customer");
        if (!customer.allowed()) {
            return customer;
        }
        if (ip == null || ip.isBlank()) {
            return Decision.pass();
        }
        return acquire("ip:" + ip, config.ipQps(), "ip");
    }

    private Decision acquire(String key, long permitsPerSecond, String dimension) {
        try {
            RRateLimiter limiter = limiter(key, permitsPerSecond);
            // 等待 0 秒：限流要的是立刻判定，让请求在桶上排队等于把延迟转嫁给用户
            if (limiter.tryAcquire(1, 0, TimeUnit.SECONDS)) {
                return Decision.pass();
            }
            rejected(dimension).increment();
            return new Decision(false, retryAfterMillis(limiter), dimension);
        } catch (RuntimeException redisUnavailable) {
            // 限流器依赖 Redis；Redis 挂了宁可放行也不能把全站变成 503
            log.warn("限流器不可用，本次放行 {}: {}", dimension, redisUnavailable.getMessage());
            return Decision.pass();
        }
    }

    private RRateLimiter limiter(String key, long permitsPerSecond) {
        return limiters.compute(key, (name, existing) -> {
            RRateLimiter limiter = redisson.getRateLimiter("shoppilot:rl:" + name);
            limiter.trySetRate(RateType.OVERALL, Math.max(1, permitsPerSecond), 1, RateIntervalUnit.SECONDS);
            return limiter;
        });
    }

    private long retryAfterMillis(RRateLimiter limiter) {
        long available = limiter.availablePermits();
        long rate = limiter.getConfig() == null ? 1 : limiter.getConfig().getRate();
        return rate <= 0 ? 1000 : Math.max(50, (long) (Math.max(0, 1 - available) * 1000.0 / rate));
    }

    /** 店铺配额来自 biz-mock 的 tenants 表，本地缓存 60 秒；取不到就用默认值。 */
    private long tenantQuota(String tenantId) {
        CachedQuota cached = quotas.get(tenantId);
        if (cached != null && cached.expiresAt() > System.nanoTime()) {
            return cached.qps();
        }
        long fetched = fetchTenantQuota(tenantId);
        quotas.put(tenantId, new CachedQuota(fetched, System.nanoTime() + TENANT_QUOTA_TTL.toNanos()));
        return fetched;
    }

    private long fetchTenantQuota(String tenantId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(bizMockBase + "/api/admin/tenants"))
                    .timeout(Duration.ofSeconds(2))
                    .header("X-Internal-Token", internalToken)
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return config.defaultTenantQps();
            }
            JsonNode tenants = mapper.readTree(response.body());
            for (JsonNode tenant : tenants) {
                if (tenantId.equals(tenant.path("tenantId").asText())) {
                    return tenant.path("rateLimitQps").asLong(config.defaultTenantQps());
                }
            }
        } catch (Exception failure) {
            log.debug("读取店铺配额失败，使用默认值: {}", failure.getMessage());
        }
        return config.defaultTenantQps();
    }

    private record CachedQuota(long qps, long expiresAt) {
    }
}
