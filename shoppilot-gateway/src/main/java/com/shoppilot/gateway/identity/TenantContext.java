package com.shoppilot.gateway.identity;

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

    public record Identity(String tenantId, String customerId, String conversationId) {
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
