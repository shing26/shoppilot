package com.shoppilot.gateway.web;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.config.OpsAccess;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.tool.identity.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 运维面的默认拒绝守卫（2026-10-22 评审 P0）。
 *
 * <p>此前 {@code /ops/feedback/review-queue} 与 {@code /ops/tenants} 只过买家 JWT，
 * 任何登录用户都能读本店点踩复核队列、枚举全平台店铺与配额。这里把口径统一成：
 * 除买家自己的 {@code /ops/tickets/mine} 外，{@code /ops/**} 必须同时满足
 * 「运维凭证匹配且运维面开启」或「令牌角色是坐席/管理员」。
 *
 * <p>为什么放在 interceptor 而不是给每个方法再加一行：逐方法手写正是漏掉那两个端点的原因；
 * 默认拒绝 + 一条显式白名单，漏一个端点会红在测试里，而不是红在线上。
 */
@Component
public class OpsAuthorizationInterceptor implements HandlerInterceptor {

    /** 买家只读自己的工单（列表与详情），仍按买家身份放行（票 92/93、UX-03 的契约）。 */
    static final String BUYER_SAFE_PREFIX = "/api/v1/support/ops/tickets/mine";

    private final GatewayProperties properties;
    private final ApiErrorWriter errors;

    public OpsAuthorizationInterceptor(GatewayProperties properties, ApiErrorWriter errors) {
        this.properties = properties;
        this.errors = errors;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String path = request.getRequestURI();
        if (path.equals(BUYER_SAFE_PREFIX) || path.startsWith(BUYER_SAFE_PREFIX + "/")) {
            return true;
        }
        OpsAccess access = opsAccess(request);
        if (access.allowed() || staff()) {
            return true;
        }
        // 保留 ADR 0028 / 票 21 的错误码语义：开关关闭与令牌不匹配是两件事。
        // 没带 token 的买家走 role.denied，不把运维面的存在感泄给普通登录用户。
        if (access == OpsAccess.DISABLED) {
            errors.write(response, HttpServletResponse.SC_FORBIDDEN, access.code(), access.message());
        } else if (request.getHeader("X-Ops-Token") != null && !request.getHeader("X-Ops-Token").isBlank()) {
            errors.write(response, HttpServletResponse.SC_FORBIDDEN, access.code(), access.message());
        } else {
            errors.write(response, HttpServletResponse.SC_FORBIDDEN, "role.denied",
                    "该端点需要运维凭证或坐席/管理员账号");
        }
        return false;
    }

    private OpsAccess opsAccess(HttpServletRequest request) {
        GatewayProperties.Ops ops = properties.ops();
        return OpsAccess.evaluate(ops.enabled(), ops.token(), request.getHeader("X-Ops-Token"));
    }

    private static boolean staff() {
        if (!TenantContext.present()) {
            return false;
        }
        UserRole role = TenantContext.current().role();
        return role == UserRole.AGENT || role == UserRole.ADMIN;
    }
}
