package com.shoppilot.tool.identity;

/**
 * 刷新令牌的一次签发结果（round56 票 224 / ADR 0086）。
 *
 * <p>{@code refreshToken} 是明文，只在 biz-mock → 网关这一跳的内部回环里出现；网关收到后
 * 立刻放进 HttpOnly Cookie，不写日志、不进响应体。库里只存它的 SHA-256。
 */
public record RefreshSession(String refreshToken, AccountView account) {
}
