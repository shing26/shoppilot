package com.shoppilot.tool.identity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * refresh token 的自描述前缀（round56 票 224 / ADR 0086）。
 *
 * <p>刷新时 access token 往往已经过期，网关拿不到可信的租户坐标；而 biz-mock 的 `users` /
 * `refresh_tokens` 都挂了 `@TenantId`，没有租户就查不到行。令牌自带租户前缀解决的是
 * 「去哪张表找」——**它不是身份断言**：真正作数的是整串令牌的 SHA-256 与库里那一行匹配，
 * 前缀被篡改只会查不到，不会跨租户命中。
 *
 * <p>格式 `v1.<base64url(tenantId)>.<secret>`；secret 是 256 bit CSPRNG。
 */
public final class RefreshTokenFormat {

    private static final String VERSION = "v1";

    private RefreshTokenFormat() {
    }

    public static String encode(String tenantId, String secret) {
        String encodedTenant = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tenantId.getBytes(StandardCharsets.UTF_8));
        return VERSION + "." + encodedTenant + "." + secret;
    }

    /** 取出前缀里的租户；格式不对返回 null（调用方按「无效刷新」处理，不猜）。 */
    public static String tenantOf(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.", 3);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return null;
        }
        try {
            String tenant = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return tenant.isBlank() ? null : tenant;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
