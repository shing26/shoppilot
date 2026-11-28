package com.shoppilot.commerce;

/**
 * 内核可见的退款生命周期。
 *
 * <pre>
 * REQUESTED -> APPROVED -> SUBMITTED -> SETTLED
 *          \-> REJECTED        \-> FAILED
 * </pre>
 *
 * <p>前两态由本地审批账本推进；后三态由商业系统回执/对账推进。
 */
public enum RefundStatus {
    REQUESTED,
    APPROVED,
    SUBMITTED,
    SETTLED,
    FAILED,
    REJECTED;

    public boolean isTerminal() {
        return this == SETTLED || this == FAILED || this == REJECTED;
    }

    public boolean canTransitionTo(RefundStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case REQUESTED -> next == APPROVED || next == REJECTED;
            case APPROVED -> next == SUBMITTED || next == FAILED;
            case SUBMITTED -> next == SETTLED || next == FAILED;
            case SETTLED, FAILED, REJECTED -> false;
        };
    }
}
