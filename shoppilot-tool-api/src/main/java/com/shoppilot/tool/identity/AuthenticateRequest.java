package com.shoppilot.tool.identity;

/**
 * 认证请求（round25 票 80）。用户名按 (租户, 用户名) 唯一，所以租户同样走请求头而不是请求体。
 */
public record AuthenticateRequest(String username, String password) {
}