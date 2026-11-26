package com.shoppilot.commerce.fake;

import com.shoppilot.commerce.CommerceError;
import com.shoppilot.commerce.CommerceResult;
import com.shoppilot.commerce.FinancialStatus;
import com.shoppilot.commerce.FulfillmentStatus;
import com.shoppilot.commerce.IdempotencyKey;
import com.shoppilot.commerce.LineItem;
import com.shoppilot.commerce.Money;
import com.shoppilot.commerce.OrderRef;
import com.shoppilot.commerce.OrderSnapshot;
import com.shoppilot.commerce.PolicyProvider;
import com.shoppilot.commerce.RefundIntent;
import com.shoppilot.commerce.RefundLine;
import com.shoppilot.commerce.RefundPolicy;
import com.shoppilot.commerce.RefundRequest;
import com.shoppilot.commerce.RefundStatus;
import com.shoppilot.commerce.RefundWorkflow;
import com.shoppilot.commerce.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RefundWorkflowTest {

    private static final TenantId TENANT = new TenantId("demo.myshopify.com");
    private static final OrderRef ORDER = new OrderRef("#1001");
    private static final Money UNIT = Money.of(5000, "CNY");
    private static final Money SHIPPING = Money.of(1000, "CNY");
    private static final Instant NOW = Instant.parse("2026-11-26T00:00:00Z");

    private final InMemoryCommerceGateway commerce = new InMemoryCommerceGateway();
    private final InMemoryRefundApprovalStore store = new InMemoryRefundApprovalStore();
    private final AtomicInteger ids = new AtomicInteger();
    private RefundWorkflow workflow;

    @BeforeEach
    void setUp() {
        commerce.seedOrder(TENANT, order());
        workflow = new RefundWorkflow(
                commerce,
                store,
                PolicyProvider.allowAll(30),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "req-" + ids.incrementAndGet());
    }

    @Test
    void requestCreatesRequestedWithoutCallingCommerce() {
        RefundRequest request = valueOf(workflow.request(TENANT, intent(1), key("r1")));

        assertThat(request.status()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(commerce.createRefundCalls()).isZero();
    }

    @Test
    void approveSubmitsToCommerce() {
        RefundRequest requested = valueOf(workflow.request(TENANT, intent(1), key("a1")));
        RefundRequest approved = valueOf(workflow.approve(TENANT, requested.id()));

        assertThat(approved.status()).isEqualTo(RefundStatus.SUBMITTED);
        assertThat(approved.commerceRefundId()).isNotBlank();
        assertThat(commerce.createRefundCalls()).isEqualTo(1);
    }

    @Test
    void repeatApproveIsStateNotAllowed() {
        RefundRequest requested = valueOf(workflow.request(TENANT, intent(1), key("a2")));
        valueOf(workflow.approve(TENANT, requested.id()));

        assertThat(errorOf(workflow.approve(TENANT, requested.id())))
                .isEqualTo(CommerceError.STATE_NOT_ALLOWED);
        assertThat(commerce.createRefundCalls()).isEqualTo(1);
    }

    @Test
    void rejectDoesNotCallCommerce() {
        RefundRequest requested = valueOf(workflow.request(TENANT, intent(1), key("j1")));
        RefundRequest rejected = valueOf(workflow.reject(TENANT, requested.id(), "customer changed mind"));

        assertThat(rejected.status()).isEqualTo(RefundStatus.REJECTED);
        assertThat(commerce.createRefundCalls()).isZero();
    }

    @Test
    void rejectedIntentCanBeRequestedAgainWithNewKey() {
        RefundRequest first = valueOf(workflow.request(TENANT, intent(1), key("j2")));
        valueOf(workflow.reject(TENANT, first.id(), "no"));

        RefundRequest second = valueOf(workflow.request(TENANT, intent(1), key("j3")));
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void sameKeyReplaysRequest() {
        RefundRequest first = valueOf(workflow.request(TENANT, intent(1), key("k1")));
        RefundRequest second = valueOf(workflow.request(TENANT, intent(1), key("k1")));

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void sameIntentDifferentKeyConflictsWhileOpen() {
        valueOf(workflow.request(TENANT, intent(1), key("k2")));

        assertThat(errorOf(workflow.request(TENANT, intent(1), key("k3"))))
                .isEqualTo(CommerceError.CONFLICT);
    }

    @Test
    void policyDenialIsStateNotAllowed() {
        RefundWorkflow denied = new RefundWorkflow(
                commerce,
                store,
                (tenant, order) -> RefundPolicy.deny("refund not allowed for this category"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "req-denied");

        assertThat(errorOf(denied.request(TENANT, intent(1), key("d1"))))
                .isEqualTo(CommerceError.STATE_NOT_ALLOWED);
    }

    @Test
    void expiredWindowIsStateNotAllowed() {
        RefundWorkflow expired = new RefundWorkflow(
                commerce,
                store,
                PolicyProvider.allowAll(0),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "req-expired");

        assertThat(errorOf(expired.request(TENANT, intent(1), key("e1"))))
                .isEqualTo(CommerceError.STATE_NOT_ALLOWED);
    }

    @Test
    void refreshSettlesAfterCommerceSuccess() {
        RefundRequest requested = valueOf(workflow.request(TENANT, intent(1), key("s1")));
        RefundRequest submitted = valueOf(workflow.approve(TENANT, requested.id()));
        commerce.markSettled(TENANT, submitted.commerceRefundId());

        RefundRequest settled = valueOf(workflow.refresh(TENANT, submitted.id()));
        assertThat(settled.status()).isEqualTo(RefundStatus.SETTLED);
    }

    @Test
    void refreshMarksFailure() {
        RefundRequest requested = valueOf(workflow.request(TENANT, intent(1), key("f1")));
        RefundRequest submitted = valueOf(workflow.approve(TENANT, requested.id()));
        commerce.markFailed(TENANT, submitted.commerceRefundId());

        RefundRequest failed = valueOf(workflow.refresh(TENANT, submitted.id()));
        assertThat(failed.status()).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    void transientFailureKeepsApprovedForRetry() {
        RefundRequest requested = valueOf(workflow.request(TENANT, intent(1), key("t1")));
        commerce.failNextCreate(CommerceError.THROTTLED);

        assertThat(errorOf(workflow.approve(TENANT, requested.id())))
                .isEqualTo(CommerceError.THROTTLED);
        assertThat(store.find(TENANT, requested.id()).orElseThrow().status())
                .isEqualTo(RefundStatus.APPROVED);

        RefundRequest submitted = valueOf(workflow.approve(TENANT, requested.id()));
        assertThat(submitted.status()).isEqualTo(RefundStatus.SUBMITTED);
        assertThat(commerce.createRefundCalls()).isEqualTo(2);
    }

    @Test
    void nonRetryableFailureMarksFailed() {
        RefundRequest requested = valueOf(workflow.request(TENANT, intent(1), key("n1")));
        commerce.failNextCreate(CommerceError.INVALID_REQUEST);

        assertThat(errorOf(workflow.approve(TENANT, requested.id())))
                .isEqualTo(CommerceError.INVALID_REQUEST);
        assertThat(store.find(TENANT, requested.id()).orElseThrow().status())
                .isEqualTo(RefundStatus.FAILED);
    }

    private static OrderSnapshot order() {
        return new OrderSnapshot(
                ORDER,
                "gid://shopify/Order/1001",
                Money.of(14000, "CNY"),
                Money.zero("CNY"),
                SHIPPING,
                false,
                FinancialStatus.PAID,
                FulfillmentStatus.FULFILLED,
                List.of(
                        new LineItem("line-a", "SKU-A", "A", 2, 0, UNIT),
                        new LineItem("line-b", "SKU-B", "B", 1, 0, Money.of(3000, "CNY"))),
                NOW.minus(2, ChronoUnit.DAYS),
                NOW.minus(1, ChronoUnit.DAYS));
    }

    private static RefundIntent intent(int quantity) {
        return new RefundIntent(
                ORDER,
                List.of(new RefundLine("line-a", quantity)),
                false,
                "test");
    }

    private static IdempotencyKey key(String suffix) {
        return new IdempotencyKey("workflow-" + suffix);
    }

    private static CommerceError errorOf(CommerceResult<?> result) {
        assertThat(result).isInstanceOf(CommerceResult.Err.class);
        return ((CommerceResult.Err<?>) result).error();
    }

    private static <T> T valueOf(CommerceResult<T> result) {
        assertThat(result).isInstanceOf(CommerceResult.Ok.class);
        return com.shoppilot.commerce.CommerceResults.unwrap(result);
    }
}
