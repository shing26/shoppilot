package com.shoppilot.gateway.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 验签并把身份写入 {@link TenantContext}，同时把四个请求坐标装进 MDC（ADR 0027）。
 *
 * <p>刻意不读任何来自 body / query / 普通 header 的 tenantId（ADR 0005 防线一）。
 * 若请求里出现这类字段，只记告警不改上下文——告警本身就是"有人在试探"的证据。
 *
 * <p>顺序有讲究：链路号先装填，再判要不要发那条告警。否则"有人在试探"这行恰恰是全仓唯一
 * 不带链路号的日志，最该被追到的一行追不到。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final JwtService jwtService;

    public AuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator/") || uri.startsWith("/auth/") || uri.equals("/")
                || uri.endsWith(".html") || uri.endsWith(".js") || uri.endsWith(".css") || uri.endsWith(".ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 清场放在最外层：401 的三条返回路径同样要清，否则线程归还池子时带着上一单的坐标
        try {
            RequestTrace.start();
            warnOnClientSuppliedTenant(request);
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                reject(response, "missing bearer token");
                return;
            }
            String conversationId = request.getHeader("X-Conversation-Id");
            if (conversationId == null || conversationId.isBlank()) {
                conversationId = UUID.randomUUID().toString();
            }
            var identity = jwtService.verify(header.substring(7).trim(), conversationId);
            if (identity.isEmpty()) {
                reject(response, "invalid or expired token");
                return;
            }
            TenantContext.set(identity.get());
            RequestTrace.bind(identity.get());
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            RequestTrace.clear();
        }
    }

    private void warnOnClientSuppliedTenant(HttpServletRequest request) {
        String suspicious = request.getParameter("tenantId");
        if (suspicious != null || request.getHeader("X-Tenant-Id") != null) {
            log.warn("忽略客户端自带的租户标识 uri={} queryTenantId={} headerTenantId={}",
                    request.getRequestURI(), suspicious, request.getHeader("X-Tenant-Id"));
        }
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
