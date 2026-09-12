package com.shoppilot.gateway.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * dev 默认凭证的合法性由绑定地址决定（ADR 0029）。

 * <p>回环绑定：三处默认值合法——干净克隆判据与三条演示都靠它，但「正在吃默认值」这件事必须能被机器读出来。
 * 非回环绑定：任何一处仍是仓库里的默认值就拒绝启动，mock 身份签发端点也不再注册。

 * <p>默认值只在本类写一份。{@code application.yml} 里那三处留空，由
 * {@link DevDefaultsEnvironmentPostProcessor} 在回环时填进来——两处各写一份迟早会悄悄分家，
 * 届时守护判的就不是真正在用的那个值了。
 */
public final class DevDefaultsPolicy {

    public static final String JWT_SECRET = "dev-jwt-secret-change-me-please-0123456789";
    public static final String INTERNAL_TOKEN = "dev-internal-token-change-me";
    public static final String OPS_TOKEN = "dev-ops-token";

    private final boolean loopback;
    private final String jwtSecret;
    private final String internalToken;
    private final String opsToken;
    private final boolean opsEnabled;

    public DevDefaultsPolicy(String bindAddress, String jwtSecret, String internalToken,
                            String opsToken, boolean opsEnabled) {
        this.loopback = isLoopback(bindAddress);
        this.jwtSecret = jwtSecret;
        this.internalToken = internalToken;
        this.opsToken = opsToken;
        this.opsEnabled = opsEnabled;
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

    public boolean loopback() {
        return loopback;
    }

    /** mock 身份签发端点是否注册：只在回环上注册。见 ADR 0029 与 ADR 0014。 */
    public boolean mockIdentityAvailable() {
        return loopback;
    }

    /** 回环上「当前正在吃哪几处仓库默认值」，供观测面自报；非回环上这个列表为空（要么已兜底要么根本起不来）。 */
    public List<String> devDefaultsInUse() {
        List<String> inUse = new ArrayList<>();
        if (stillDefault(jwtSecret, JWT_SECRET)) {
            inUse.add("SHOPPILOT_JWT_SECRET");
        }
        if (stillDefault(internalToken, INTERNAL_TOKEN)) {
            inUse.add("SHOPPILOT_INTERNAL_TOKEN");
        }
        if (opsEnabled && stillDefault(opsToken, OPS_TOKEN)) {
            inUse.add("SHOPPILOT_OPS_TOKEN");
        }
        return Collections.unmodifiableList(inUse);
    }

    /** 非回环时的启动阻断项，逐条点名到环境变量；回环上恒为空。 */
    public List<String> startupBlockers() {
        if (loopback) {
            return List.of();
        }
        List<String> blockers = new ArrayList<>();
        if (stillDefault(jwtSecret, JWT_SECRET)) {
            blockers.add("SHOPPILOT_JWT_SECRET 未覆盖：仓库里那个默认值能签出任意店铺与买家的合法身份");
        }
        if (stillDefault(internalToken, INTERNAL_TOKEN)) {
            blockers.add("SHOPPILOT_INTERNAL_TOKEN 未覆盖：网关到业务层的内部令牌明文在仓库里");
        }
        if (opsEnabled && stillDefault(opsToken, OPS_TOKEN)) {
            blockers.add("SHOPPILOT_OPS_TOKEN 未覆盖，运维端点也没显式关闭（SHOPPILOT_OPS_ENABLED=false）：故障注入与缓存清理是改状态的动作");
        }
        return List.copyOf(blockers);
    }

    /** 未设置、空串、等于仓库默认值，三者都算「仍在吃默认值」。 */
    private static boolean stillDefault(String actual, String repoDefault) {
        return actual == null || actual.isBlank() || Objects.equals(actual.trim(), repoDefault);
    }
}
