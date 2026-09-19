package com.shoppilot.gateway.web;

import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.channel.ChannelContext;
import com.shoppilot.gateway.agent.FallbackService;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.ratelimit.RateLimitService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 入站准入的单一实现（ADR 0035 的"拆出归一层"；CODE_MAP 预告的 ChatAdmission）。
 *
 * <p>四条入站路径（/chat、/chat/stream、/webhook/{channel}、/email）共用它：
 * 请求计数、渠道计数、限流判定与"被限流也留可查工单"的落单窗口都在这一处，
 * 新增准入规则只改这里，不出现第四条会漏改的分支。
 */
@Component
public class ChatAdmission {

    private final CacheService cacheService;
    private final RateLimitService rateLimit;
    private final FallbackService fallbackService;
    private final MeterRegistry registry;

    public ChatAdmission(CacheService cacheService, RateLimitService rateLimit, FallbackService fallbackService,
                         MeterRegistry registry) {
        this.cacheService = cacheService;
        this.rateLimit = rateLimit;
        this.fallbackService = fallbackService;
        this.registry = registry;
    }

    public Guard check(HttpServletRequest request, String query) {
        cacheService.recordRequest();
        registry.counter("shoppilot_channel_requests_total", "channel", ChannelContext.current().label()).increment();
        RateLimitService.Decision decision = rateLimit.tryAcquire(TenantContext.tenantId(), TenantContext.customerId(),
                request.getRemoteAddr());
        if (decision.allowed()) {
            return new Guard(decision, null);
        }
        TenantContext.Identity identity = TenantContext.current();
        String ticketId = fallbackService.escalateRateLimited(query, identity.tenantId(), identity.customerId())
                .orElse(null);
        return new Guard(decision, ticketId);
    }

    /** @param ticketId 被限流时留下的可查工单（ADR 0009 的落单窗口），放行时为 null */
    public record Guard(RateLimitService.Decision decision, String ticketId) {

        public boolean allowed() {
            return decision.allowed();
        }

        public long retryAfterMs() {
            return decision.retryAfterMs();
        }
    }
}
