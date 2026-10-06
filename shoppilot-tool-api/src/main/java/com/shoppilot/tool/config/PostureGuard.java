package com.shoppilot.tool.config;

/**
 * 凭据姿势原语（round29 票 104 / ADR 0062）。
 *
 * <p>「dev 默认凭证是否合法」由绑定地址决定（ADR 0029 的语义）。这条语义此前只活在
 * 网关的 {@code DevDefaultsPolicy} 里，而持有业务数据与账号的 biz-mock / ticket 在容器档
 * 同样绑非回环——各进程抄一份迟早分家，所以原语下沉到四个模块共享的本库；
 * 网关的 Policy 委托到这里，公开 API 与行为逐字节不变。
 *
 * <p>仓库默认值常量也住在这里：它们是**全仓**的默认值（biz-mock / ticket 的 yml 占位符
 * 里的那份字面量与它必须逐字一致），不是网关的私有物。
 */
public final class PostureGuard {

    public static final String JWT_SECRET = "dev-jwt-secret-change-me-please-0123456789";
    public static final String INTERNAL_TOKEN = "dev-internal-token-change-me";
    public static final String OPS_TOKEN = "dev-ops-token";

    private PostureGuard() {
    }

    /**
     * 地址为空按「未限定监听接口」处理，也就是非回环：不配 {@code server.address} 时容器监听所有网卡，
     * 这条判据必须 fail-closed，不能把「没填」当成「填了回环」。
     */
    public static boolean isLoopback(String bindAddress) {
        if (bindAddress == null || bindAddress.isBlank()) {
            return false;
        }
        String a = bindAddress.trim().toLowerCase();
        return a.equals("localhost") || a.equals("::1") || a.equals("[::1]") || a.startsWith("127.");
    }

    /** 未设置、空串、等于仓库默认值，三者都算「仍在吃默认值」。 */
    public static boolean stillDefault(String actual, String repoDefault) {
        return actual == null || actual.isBlank() || actual.trim().equals(repoDefault);
    }
}
