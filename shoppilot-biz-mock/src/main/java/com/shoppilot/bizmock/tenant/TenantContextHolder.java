package com.shoppilot.bizmock.tenant;

/**
 * 当前请求的租户与买家上下文。
 *
 * <p>身份只由 {@code InternalAuthFilter} 在校验内部服务凭证后写入，
 * 业务代码一律从这里取，不接受任何其他来源（ADR 0005 防线一在 biz-mock 侧的对应实现）。
 */
public final class TenantContextHolder {

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<String> CUSTOMER = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static void set(String tenantId, String customerId) {
        TENANT.set(tenantId);
        CUSTOMER.set(customerId);
    }

    public static String tenantId() {
        String tenantId = TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException("缺少租户上下文，拒绝执行");
        }
        return tenantId;
    }

    /**
     * 当前租户，**没有上下文时返回 null 而不是抛**。
     *
     * <p>给平台级运维路径用：{@code /api/admin/stats} 是跨租户口径，它没有租户上下文，
     * 而 {@link #tenantId()} 在那种上下文下会抛——于是「取个工单数」变成一次异常兜底返 0，
     * 面板上永远显示 0（清场日实测到的数）。业务路径仍然用 {@link #tenantId()}，
     * 那里的「没有上下文就抛」是防线。
     */
    public static String tenantIdOrNull() {
        return TENANT.get();
    }

    public static String customerId() {
        return CUSTOMER.get();
    }

    public static boolean hasCustomer() {
        return CUSTOMER.get() != null;
    }

    /** 虚拟线程下 ThreadLocal 不会自动回收，必须在 filter 的 finally 里显式清理。 */
    public static void clear() {
        TENANT.remove();
        CUSTOMER.remove();
    }
}
