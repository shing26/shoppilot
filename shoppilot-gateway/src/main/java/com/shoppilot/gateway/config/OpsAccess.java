package com.shoppilot.gateway.config;

/**
 * 运维凭证的判定结果。以前这里只返回一个布尔，于是「开关被关掉」与「令牌不对」
 * 在调用点上长成同一句话，同一个失败在不同端点又各写一遍文案。拆成三个值，
 * 文案由这里统一给（错误体形状是票 25 的事）。
 */
public enum OpsAccess {

    ALLOWED("ops.allowed", "ok"),
    DISABLED("ops.disabled", "ops endpoints are disabled (shoppilot.ops.enabled=false)"),
    TOKEN_MISMATCH("ops.token_mismatch", "ops token missing or mismatched");

    private final String code;
    private final String message;

    OpsAccess(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 先判开关再判令牌：开关关着时不要让人以为是自己令牌填错了。 */
    public static OpsAccess evaluate(boolean enabled, String expectedToken, String suppliedToken) {
        if (!enabled) {
            return DISABLED;
        }
        return expectedToken != null && expectedToken.equals(suppliedToken) ? ALLOWED : TOKEN_MISMATCH;
    }

    public boolean allowed() {
        return this == ALLOWED;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
