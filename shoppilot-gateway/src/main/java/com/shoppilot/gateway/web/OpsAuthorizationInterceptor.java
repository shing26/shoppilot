package com.shoppilot.gateway.web;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.config.OpsAccess;
import com.shoppilot.gateway.identity.Capabilities;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.tool.identity.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

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

    /** 管理员专属面：渠道凭据与店铺枚举。坐席账号不再能读写（票 221）。 */
    static final String CHANNELS_PREFIX = "/api/v1/support/ops/channels";
    static final String TENANTS_PREFIX = "/api/v1/support/ops/tenants";

    /**
     * 平台动作面：故障注入、清缓存、纪元、演示复位、负缓存、检索探针与模型故障。
     *
     * <p>这些端点改的是平台状态，不是某个坐席的日常工作；只认 {@code X-Ops-Token}，
     * 不接受 AGENT/ADMIN 账号。集中放在拦截器里，避免逐方法守卫漏掉其中一个
     * （票 221 的 `GET /llm-fault` 就是这样漏掉的）。
     */
    private static final java.util.Set<String> PLATFORM_ONLY_PATHS = java.util.Set.of(
            "/api/v1/support/ops/fault",
            "/api/v1/support/ops/stats",
            "/api/v1/support/ops/demo/reset",
            "/api/v1/support/ops/retrieval",
            "/api/v1/support/ops/epoch/bump",
            "/api/v1/support/ops/cache/flush",
            "/api/v1/support/ops/llm-fault",
            "/api/v1/support/ops/negative-cache");

    private final GatewayProperties properties;
    private final ApiErrorWriter errors;

    public OpsAuthorizationInterceptor(GatewayProperties properties, ApiErrorWriter errors) {
        this.properties = properties;
        this.errors = errors;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // Spring MVC 会在路由前剥离分号矩阵参数（/channels;x → /channels），
        // 但 getRequestURI() 保留原样。若直接拿原始 URI 比对，同一 handler 就能用
        // /channels;x 绕过前缀判定。这里与 MVC 使用同一个路径解析口径。
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (path.equals(BUYER_SAFE_PREFIX) || path.startsWith(BUYER_SAFE_PREFIX + "/")) {
            return true;
        }
        OpsAccess access = opsAccess(request);
        if (access.allowed()) {
            // 平台凭证是运维者的兜底路径；它不是 ADMIN 用户能力，也不进 capabilities。
            return true;
        }
        UserRole role = currentRole();
        if (isPlatformOnly(path)) {
            if (access == OpsAccess.DISABLED) {
                errors.write(response, HttpServletResponse.SC_FORBIDDEN, access.code(), access.message());
            } else if (hasOpsToken(request)) {
                errors.write(response, HttpServletResponse.SC_FORBIDDEN, access.code(), access.message());
            } else {
                errors.write(response, HttpServletResponse.SC_FORBIDDEN, "role.denied",
                        "该端点只接受运维凭证");
            }
            return false;
        }
        if (isAdminOnly(path)) {
            if (adminOnlyAllowed(role)) {
                return true;
            }
            // 保留 ADR 0028 / 票 21 的错误码语义：开关关闭与令牌不匹配是两件事。
            if (access == OpsAccess.DISABLED) {
                errors.write(response, HttpServletResponse.SC_FORBIDDEN, access.code(), access.message());
            } else if (hasOpsToken(request)) {
                errors.write(response, HttpServletResponse.SC_FORBIDDEN, access.code(), access.message());
            } else if (role == UserRole.AGENT) {
                errors.write(response, HttpServletResponse.SC_FORBIDDEN, "role.denied",
                        "该功能需要管理员账号");
            } else {
                errors.write(response, HttpServletResponse.SC_FORBIDDEN, "role.denied",
                        "该端点需要运维凭证或管理员账号");
            }
            return false;
        }
        if (staff(role)) {
            return true;
        }
        // 没带 token 的买家走 role.denied，不把运维面的存在感泄给普通登录用户。
        if (access == OpsAccess.DISABLED) {
            errors.write(response, HttpServletResponse.SC_FORBIDDEN, access.code(), access.message());
        } else if (hasOpsToken(request)) {
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

    private static boolean staff(UserRole role) {
        return role == UserRole.AGENT || role == UserRole.ADMIN;
    }

    private static UserRole currentRole() {
        return TenantContext.present() ? TenantContext.current().role() : null;
    }

    private static boolean isAdminOnly(String path) {
        return underPrefix(path, CHANNELS_PREFIX) || underPrefix(path, TENANTS_PREFIX);
    }

    private static boolean isPlatformOnly(String path) {
        return PLATFORM_ONLY_PATHS.contains(path);
    }

    private static boolean underPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * 管理员专属面只认两项管理员能力同时具备的角色。
     *
     * <p>不写成 {@code role == ADMIN} 是为了让角色到能力的映射只留在 {@link Capabilities}：
     * 将来若新增一个只读管理员，改一处映射即可，不需要在拦截器里再找一遍角色比较。
     */
    private static boolean adminOnlyAllowed(UserRole role) {
        return role != null
                && Capabilities.allows(role, Capabilities.CHANNELS)
                && Capabilities.allows(role, Capabilities.TENANTS);
    }

    private static boolean hasOpsToken(HttpServletRequest request) {
        String token = request.getHeader("X-Ops-Token");
        return token != null && !token.isBlank();
    }
}
