package com.shoppilot.commerce;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefundStatusTest {

    @Test
    void requestedCanBeApprovedOrRejected() {
        assertThat(RefundStatus.REQUESTED.canTransitionTo(RefundStatus.APPROVED)).isTrue();
        assertThat(RefundStatus.REQUESTED.canTransitionTo(RefundStatus.REJECTED)).isTrue();
        assertThat(RefundStatus.REQUESTED.canTransitionTo(RefundStatus.SUBMITTED)).isFalse();
    }

    @Test
    void approvedCanBeSubmittedOrFailed() {
        assertThat(RefundStatus.APPROVED.canTransitionTo(RefundStatus.SUBMITTED)).isTrue();
        assertThat(RefundStatus.APPROVED.canTransitionTo(RefundStatus.FAILED)).isTrue();
        assertThat(RefundStatus.APPROVED.canTransitionTo(RefundStatus.REQUESTED)).isFalse();
    }

    @Test
    void submittedCanOnlySettleOrFail() {
        assertThat(RefundStatus.SUBMITTED.canTransitionTo(RefundStatus.SETTLED)).isTrue();
        assertThat(RefundStatus.SUBMITTED.canTransitionTo(RefundStatus.FAILED)).isTrue();
        assertThat(RefundStatus.SUBMITTED.canTransitionTo(RefundStatus.APPROVED)).isFalse();
    }

    @Test
    void terminalStatesAreFinal() {
        for (RefundStatus terminal : new RefundStatus[]{
                RefundStatus.SETTLED, RefundStatus.FAILED, RefundStatus.REJECTED}) {
            assertThat(terminal.isTerminal()).isTrue();
            for (RefundStatus next : RefundStatus.values()) {
                assertThat(terminal.canTransitionTo(next)).isFalse();
            }
        }
    }
}
