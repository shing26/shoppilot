package com.shoppilot.gateway.identity;

import com.shoppilot.tool.identity.UserRole;

/**
 * 服务端身份上下文。唯一写入方是 {@code AuthFilter}（ADR 0005 防线一、ADR 0014）。
 *
 * <p>下游取身份只能经这里：请求体、查询参数、Header 里的 tenantId 一律不读。
 * 该约束由 IdentityArchitectureTest 用 ArchUnit 守住。
 *
 * <p>用 ThreadLocal 而非 ScopedValue：后者在 Java 21 仍是 preview，需要 --enable-preview，
 * 不为一个简历关键词给构建链加风险。虚拟线程下必须在 finally 显式 clear。
 */
public final class TenantContext {

    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 已验签的身份。
     *
     * <p>{@code role} 与 {@code accountId} 是 round25 票 81 加的两个坐标：角色守卫（票 82）判「这个角色
     * 能不能做这个动作」，审计事件（ADR 0056）记「是谁做的」。**两者都取自令牌，不取自任何请求头**——
     * 自报身份退役之后，能作数的主体只剩这一个。
     *
     * <p>三参构造保留下来：它就是「一个普通买家会话」，仓里 19 处测试与 mock 令牌都走它。
     * 加一个四参或五参的便利构造而不是改那 19 处，不是为了省事——是因为「没有角色的会话」
     * 本身就该是一个明确写出来的概念，而不是让人在每个测试里补一个 `UserRole.BUYER`。
     */
    public record Identity(String tenantId, String customerId, String conversationId,
                           UserRole role, String accountId) {

        public Identity(String tenantId, String customerId, String conversationId) {
            this(tenantId, customerId, conversationId, UserRole.BUYER, null);
        }

        /** 账号 id；mock 令牌与历史令牌没有账号，返回 null（**不是**空串——空串会被当成一个叫空的账号）。 */
        public String actor() {
            return accountId != null && !accountId.isBlank() ? accountId : customerId;
        }
    }

    public static void set(Identity identity) {
        CURRENT.set(identity);
    }

    public static Identity current() {
        Identity identity = CURRENT.get();
        if (identity == null) {
            throw new IllegalStateException("缺少已验签身份，拒绝执行业务查询");
        }
        return identity;
    }

    public static boolean present() {
        return CURRENT.get() != null;
    }

    public static String tenantId() {
        return current().tenantId();
    }

    public static String customerId() {
        return current().customerId();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
