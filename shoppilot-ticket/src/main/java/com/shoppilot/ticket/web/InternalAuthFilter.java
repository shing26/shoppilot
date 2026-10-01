package com.shoppilot.ticket.web;

import com.shoppilot.ticket.tenant.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 工单服务的唯一调用方是网关。内部服务凭证缺失或不匹配一律 401，
 * 防止绕过网关直打 :8092（沿用 biz-mock 的同一道门，ticket 15 的教训）。
 *
 * <p>身份从网关已验签的上下文头里取，本服务不自行签发身份（ADR 0014）。
 *
 * <p>另外：坐席身份与审核人身份（{@code X-Reviewer}）都还是**调用方自报**的，
 * 身份域是下一轮（ADR 0056）。所以它们进的是审计不是权限。
 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    private final String internalToken;

    public InternalAuthFilter(@Value("${shoppilot.ticket.internal-token}") String internalToken) {
        this.internalToken = internalToken;
    }

    /** 健康检查与指标不需要身份，否则容器 healthcheck 与验收脚本都过不去。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!internalToken.equals(request.getHeader("X-Internal-Token"))) {
            reject(response, "missing or invalid internal token");
            return;
        }
        String tenantId = blankToNull(request.getHeader("X-Tenant-Id"));
        if (tenantId == null) {
            reject(response, "missing tenant context");
            return;
        }
        try {
            TenantContextHolder.set(tenantId, blankToNull(request.getHeader("X-Customer-Id")));
            chain.doFilter(request, response);
        } finally {
            // 虚拟线程下必须显式清理，否则线程复用时身份串号
            TenantContextHolder.clear();
        }
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}