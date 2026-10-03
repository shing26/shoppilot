package com.shoppilot.tool.identity;

/**
 * 注册请求（round25 票 80）。
 *
 * <p>租户不在请求体里：它由网关按已选店铺放进 {@code X-Tenant-Id}，与仓内其余内部调用同一口径
 * （ADR 0005 防线一——身份不从 body 取）。
 */
public record RegisterAccountRequest(String username, String password, String displayName) {
}