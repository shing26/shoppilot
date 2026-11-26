package com.shoppilot.commerce;

/**
 * 类型化失败。是否能重试是契约的一部分，不交给调用方猜。
 */
public enum CommerceError {
    NOT_FOUND(false),
    STATE_NOT_ALLOWED(false),
    INVALID_REQUEST(false),
    CONFLICT(false),
    THROTTLED(true),
    UNAVAILABLE(true),
    TIMEOUT(true),
    INTERNAL(false);

    private final boolean retryable;

    CommerceError(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
