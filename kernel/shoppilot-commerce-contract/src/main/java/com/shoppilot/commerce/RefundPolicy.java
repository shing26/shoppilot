package com.shoppilot.commerce;

import java.time.Duration;
import java.time.Instant;

/**
 * 退款政策。7 天窗口属于商户政策，不是 Shopify 平台规则。
 */
public record RefundPolicy(boolean allowed, int windowDays, String denyReason) {

    public RefundPolicy {
        if (windowDays < 0) {
            throw new IllegalArgumentException("windowDays must be >= 0");
        }
        denyReason = denyReason == null ? "" : denyReason.trim();
    }

    public static RefundPolicy allow(int windowDays) {
        return new RefundPolicy(true, windowDays, "");
    }

    public static RefundPolicy deny(String reason) {
        return new RefundPolicy(false, 0, reason);
    }

    public boolean withinWindow(Instant paidAt, Instant now) {
        if (!allowed || paidAt == null) {
            return false;
        }
        return !paidAt.plus(Duration.ofDays(windowDays)).isBefore(now);
    }
}
