package com.shoppilot.tool.identity;

/** 改密请求（round56 票 224 / ADR 0086）。账号由网关从已验签令牌取，不在请求体里。 */
public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
