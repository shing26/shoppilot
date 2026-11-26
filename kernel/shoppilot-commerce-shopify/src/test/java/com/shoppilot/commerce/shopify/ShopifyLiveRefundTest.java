package com.shoppilot.commerce.shopify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.commerce.CommerceError;
import com.shoppilot.commerce.CommerceResult;
import com.shoppilot.commerce.CommerceResults;
import com.shoppilot.commerce.FinancialStatus;
import com.shoppilot.commerce.IdempotencyKey;
import com.shoppilot.commerce.OrderRef;
import com.shoppilot.commerce.RefundIntent;
import com.shoppilot.commerce.RefundLine;
import com.shoppilot.commerce.RefundReceipt;
import com.shoppilot.commerce.RefundStatus;
import com.shoppilot.commerce.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 需要一张“已捕获支付、可退数量 >= 2”的开发商店订单。
 *
 * <pre>
 * SHOPIFY_SHOP=demo.myshopify.com
 * SHOPIFY_TOKEN=shpat_...
 * SHOPIFY_LIVE_ORDER=#1009
 * SHOPIFY_LIVE_WRITE=1
 * </pre>
 *
 * <p>默认跳过；仅在显式允许写操作时运行。每跑一次会消耗订单的可退数量。
 */
class ShopifyLiveRefundTest {

    @Test
    void submitsPartialRefundReplaysAndSettles() {
        String shop = System.getenv("SHOPIFY_SHOP");
        String token = System.getenv("SHOPIFY_TOKEN");
        String orderName = System.getenv("SHOPIFY_LIVE_ORDER");
        boolean write = "1".equals(System.getenv("SHOPIFY_LIVE_WRITE"));
        assumeTrue(shop != null && !shop.isBlank()
                        && token != null && !token.isBlank()
                        && orderName != null && !orderName.isBlank()
                        && write,
                "set SHOPIFY_SHOP, SHOPIFY_TOKEN, SHOPIFY_LIVE_ORDER and SHOPIFY_LIVE_WRITE=1");

        TenantId tenant = new TenantId(shop);
        var transport = new HttpShopifyGraphQlTransport(t -> {
            if (!t.equals(tenant)) {
                throw new ShopifyTransportException("unexpected tenant: " + t.value(), null);
            }
            return token;
        });
        var gateway = new ShopifyCommerceGateway(transport, new ObjectMapper());
        OrderRef order = new OrderRef(orderName);

        var before = call("getOrder", () -> gateway.getOrder(tenant, order));
        var line = before.lineItems().stream()
                .filter(item -> item.refundableQuantity() > 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("order has no refundable line: " + orderName));
        assumeTrue(line.refundableQuantity() >= 2,
                "live order must have at least two refundable units on the selected line");
        RefundIntent intent = new RefundIntent(
                order, List.of(new RefundLine(line.id(), 1)), false, "live adapter refund");
        var quote = call("quoteRefund", () -> gateway.quoteRefund(tenant, intent));
        IdempotencyKey key = new IdempotencyKey(
                "live-" + orderName.replace("#", "") + "-" + Instant.now().toEpochMilli());

        RefundReceipt first = call("createRefund", () -> gateway.createRefund(tenant, intent, key));
        assertThat(first.status()).isEqualTo(RefundStatus.SETTLED);
        assertThat(first.amount()).isEqualTo(quote.amount());

        CommerceResult<RefundReceipt> replayResult = gateway.createRefund(tenant, intent, key);
        if (!replayResult.isOk()) {
            System.out.println("live replay failed: " + replayResult);
        }
        RefundReceipt replay = unwrap(replayResult);
        assertThat(replay.commerceRefundId()).isEqualTo(first.commerceRefundId());
        assertThat(replay.amount()).isEqualTo(quote.amount());

        assertThat(call("getRefundStatus",
                () -> gateway.getRefundStatus(tenant, first.commerceRefundId())))
                .isEqualTo(RefundStatus.SETTLED);

        var after = call("getOrder", () -> gateway.getOrder(tenant, order));
        boolean hasRemaining = after.lineItems().stream()
                .anyMatch(item -> item.refundableQuantity() > 0);
        if (hasRemaining) {
            RefundIntent changed = new RefundIntent(
                    order, intent.lines(), false, "live adapter refund changed");
            CommerceResult<RefundReceipt> conflict = gateway.createRefund(tenant, changed, key);
            assertThat(conflict).isInstanceOf(CommerceResult.Err.class);
            assertThat(((CommerceResult.Err<?>) conflict).error())
                    .isEqualTo(CommerceError.INVALID_REQUEST);

            RefundReceipt last = call("createRefund-final", () -> gateway.createRefund(
                    tenant, intent, new IdempotencyKey(key.value() + "-final")));
            assertThat(last.status()).isEqualTo(RefundStatus.SETTLED);
            assertThat(call("getOrder", () -> gateway.getOrder(tenant, order)).financial())
                    .isEqualTo(FinancialStatus.REFUNDED);

            RefundReceipt replayAfterEmpty = unwrap(
                    gateway.createRefund(tenant, intent, key));
            assertThat(replayAfterEmpty.commerceRefundId())
                    .isEqualTo(first.commerceRefundId());
            assertThat(replayAfterEmpty.amount()).isEqualTo(first.amount());
        }

        System.out.printf(
                "live refund order=%s refund=%s amount=%s status=%s remaining=%s%n",
                orderName, first.commerceRefundId(), first.amount(), first.status(),
                hasRemaining);
    }

    private static <T> T unwrap(CommerceResult<T> result) {
        if (!result.isOk()) {
            throw new AssertionError("live Shopify call failed: " + result);
        }
        return CommerceResults.unwrap(result);
    }

    private static <T> T call(String label, Supplier<CommerceResult<T>> action) {
        CommerceResult<T> last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            last = action.get();
            if (last.isOk()) {
                return CommerceResults.unwrap(last);
            }
            CommerceError error = ((CommerceResult.Err<T>) last).error();
            if (error != CommerceError.UNAVAILABLE && error != CommerceError.THROTTLED) {
                break;
            }
            System.out.println(label + " attempt " + attempt + " failed: " + last);
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(label + " failed: " + last);
    }
}
