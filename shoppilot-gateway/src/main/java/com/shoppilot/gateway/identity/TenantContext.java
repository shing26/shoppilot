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
                           UserRole role, String accountId, String username) {

        public Identity(String tenantId, String customerId, String conversationId) {
            this(tenantId, customerId, conversationId, UserRole.BUYER, null, null);
        }

        public Identity(String tenantId, String customerId, String conversationId,
                        UserRole role, String accountId) {
            this(tenantId, customerId, conversationId, role, accountId, null);
        }

        /**
         * 操作人署名，优先级 <b>用户名 → 账号 id → 买家 id</b>。
         *
         * <p>用户名排第一不是为了好看：工单的 {@code assignee} 栏与审计的 {@code actor} 栏是人读的，
         * 一串 {@code U0a1b2c3d} 记在那儿既没人认得，也无法在验收脚本里当断言用。
         * 用户名在租户内唯一，而审计行本来就带租户，所以它不会把两个身份混成一条。
         *
         * <p>mock 令牌与老令牌没有 username 也没有账号 id，退回买家 id —— 那正是它们本来的形状。
         */
        public String actor() {
            if (username != null && !username.isBlank()) {
                return username;
            }
            if (accountId != null && !accountId.isBlank()) {
                return accountId;
            }
            return customerId;
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
