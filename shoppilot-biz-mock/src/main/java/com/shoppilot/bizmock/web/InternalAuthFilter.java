package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.tenant.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * biz-mock 的唯一调用方是网关。内部服务凭证缺失或不匹配一律 401，
 * 防止绕过网关直打 :8091（ticket 15 的代理端点因此存在）。
 *
 * <p>身份从网关已验签的上下文头里取，本服务不自行签发身份（ADR 0014）。
 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    private final String internalToken;

    public InternalAuthFilter(@Value("${shoppilot.bizmock.internal-token}") String internalToken) {
        this.internalToken = internalToken;
    }

    /** 健康检查与指标不需要身份，否则容器 healthcheck 与 ticket 01 验收都过不去。 */
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
        // 平台侧运维端点（故障注入、统计、重跑 seed）跨租户，不要求租户上下文
        boolean platformScoped = request.getRequestURI().startsWith("/api/admin/");
        String tenantId = blankToNull(request.getHeader("X-Tenant-Id"));
        if (!platformScoped && tenantId == null) {
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
