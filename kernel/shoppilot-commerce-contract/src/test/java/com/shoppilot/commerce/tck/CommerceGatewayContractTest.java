package com.shoppilot.commerce.tck;

import com.shoppilot.commerce.CommerceError;
import com.shoppilot.commerce.CommerceGateway;
import com.shoppilot.commerce.CommerceResult;
import com.shoppilot.commerce.CommerceResults;
import com.shoppilot.commerce.IdempotencyKey;
import com.shoppilot.commerce.Money;
import com.shoppilot.commerce.OrderRef;
import com.shoppilot.commerce.OrderSnapshot;
import com.shoppilot.commerce.RefundIntent;
import com.shoppilot.commerce.RefundLine;
import com.shoppilot.commerce.RefundQuote;
import com.shoppilot.commerce.RefundReceipt;
import com.shoppilot.commerce.RefundStatus;
import com.shoppilot.commerce.TenantId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任何 {@link CommerceGateway} 适配器都必须通过的契约测试。
 *
 * <p>它只使用公开端口，不依赖参考实现；Shopify 适配器继承它并注入自己的 fixture。
 * 注意：这里刻意允许“同一 intent 换 key 再退一次”，因为部分退款是合法业务；
 * “同一 intent 不能重复起请求”由 {@code RefundWorkflow} 的本地审批账本保证。
 * 通过 {@code test-jar} 发布，适配器以 test scope 依赖；运行时 jar 不含 JUnit。
 */
public abstract class CommerceGatewayContractTest {

    protected abstract CommerceGateway gateway();

    protected abstract TenantId tenant();

    protected abstract TenantId otherTenant();

    protected abstract OrderRef paidOrder();

    protected abstract OrderRef uncapturedOrder();

    protected abstract String lineItemId();

    protected abstract Money lineItemUnitPrice();

    protected abstract Money shipping();

    protected RefundIntent intent(int quantity, boolean refundShipping) {
        List<RefundLine> lines = quantity == 0
                ? List.of()
                : List.of(new RefundLine(lineItemId(), quantity));
        return new RefundIntent(paidOrder(), lines, refundShipping, "contract test");
    }

    protected IdempotencyKey key(String suffix) {
        return new IdempotencyKey("tck-" + suffix);
    }

    @Test
    void unknownOrderIsNotFound() {
        assertThat(errorOf(gateway().getOrder(tenant(), new OrderRef("missing"))))
                .isEqualTo(CommerceError.NOT_FOUND);
    }

    @Test
    void crossTenantOrderIsNotFound() {
        assertThat(errorOf(gateway().getOrder(otherTenant(), paidOrder())))
                .isEqualTo(CommerceError.NOT_FOUND);
    }

    @Test
    void quoteUsesUnitPriceTimesQuantity() {
        RefundQuote quote = valueOf(gateway().quoteRefund(tenant(), intent(1, false)));
        assertThat(quote.amount()).isEqualTo(lineItemUnitPrice());
        assertThat(quote.shipping()).isEqualTo(Money.zero(lineItemUnitPrice().currency()));
    }

    @Test
    void quoteRejectsUncapturedOrder() {
        RefundIntent uncaptured = new RefundIntent(
                uncapturedOrder(),
                List.of(new RefundLine(lineItemId(), 1)),
                false,
                "uncaptured");
        assertThat(errorOf(gateway().quoteRefund(tenant(), uncaptured)))
                .isEqualTo(CommerceError.STATE_NOT_ALLOWED);
    }

    @Test
    void quoteRejectsQuantityAboveRefundable() {
        assertThat(errorOf(gateway().quoteRefund(tenant(), intent(99, false))))
                .isEqualTo(CommerceError.STATE_NOT_ALLOWED);
    }

    @Test
    void quoteRejectsUnknownLineItem() {
        RefundIntent unknown = new RefundIntent(
                paidOrder(),
                List.of(new RefundLine("missing-line", 1)),
                false,
                "unknown");
        assertThat(errorOf(gateway().quoteRefund(tenant(), unknown)))
                .isEqualTo(CommerceError.INVALID_REQUEST);
    }

    @Test
    void quoteIncludesShippingOnlyWhenRequested() {
        RefundQuote without = valueOf(gateway().quoteRefund(tenant(), intent(1, false)));
        RefundQuote with = valueOf(gateway().quoteRefund(tenant(), intent(1, true)));

        assertThat(with.amount()).isEqualTo(without.amount().plus(shipping()));
        assertThat(with.shipping()).isEqualTo(shipping());
    }

    @Test
    void shippingOnlyRefundIsQuoted() {
        RefundQuote quote = valueOf(gateway().quoteRefund(tenant(), intent(0, true)));
        assertThat(quote.amount()).isEqualTo(shipping());
    }

    @Test
    void createRefundReturnsSubmittedReceipt() {
        RefundReceipt receipt = valueOf(gateway().createRefund(tenant(), intent(1, false), key("submit")));

        assertThat(receipt.status()).isEqualTo(RefundStatus.SUBMITTED);
        assertThat(receipt.amount()).isEqualTo(lineItemUnitPrice());
        assertThat(receipt.commerceRefundId()).isNotBlank();
    }

    @Test
    void sameKeyReplaysSameReceipt() {
        IdempotencyKey key = key("replay");
        RefundReceipt first = valueOf(gateway().createRefund(tenant(), intent(1, false), key));
        RefundReceipt second = valueOf(gateway().createRefund(tenant(), intent(1, false), key));

        assertThat(second.commerceRefundId()).isEqualTo(first.commerceRefundId());
    }

    @Test
    void sameKeyDifferentIntentIsInvalid() {
        IdempotencyKey key = key("reuse");
        valueOf(gateway().createRefund(tenant(), intent(1, false), key));

        assertThat(errorOf(gateway().createRefund(tenant(), intent(1, true), key)))
                .isEqualTo(CommerceError.INVALID_REQUEST);
    }

    @Test
    void differentKeySameIntentIsAllowedWhenQuantityRemains() {
        RefundReceipt first = valueOf(gateway().createRefund(tenant(), intent(1, false), key("partial-1")));
        RefundReceipt second = valueOf(gateway().createRefund(tenant(), intent(1, false), key("partial-2")));

        assertThat(second.commerceRefundId()).isNotEqualTo(first.commerceRefundId());
    }

    @Test
    void quoteAfterRefundSeesReducedRemaining() {
        valueOf(gateway().createRefund(tenant(), intent(1, false), key("reduce")));

        assertThat(errorOf(gateway().quoteRefund(tenant(), intent(2, false))))
                .isEqualTo(CommerceError.STATE_NOT_ALLOWED);
    }

    protected static CommerceError errorOf(CommerceResult<?> result) {
        assertThat(result).isInstanceOf(CommerceResult.Err.class);
        return ((CommerceResult.Err<?>) result).error();
    }

    protected static <T> T valueOf(CommerceResult<T> result) {
        assertThat(result).isInstanceOf(CommerceResult.Ok.class);
        return CommerceResults.unwrap(result);
    }
}
