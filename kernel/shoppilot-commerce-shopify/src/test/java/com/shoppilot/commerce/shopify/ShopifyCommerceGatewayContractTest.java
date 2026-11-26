package com.shoppilot.commerce.shopify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppilot.commerce.CommerceGateway;
import com.shoppilot.commerce.CommerceResults;
import com.shoppilot.commerce.Money;
import com.shoppilot.commerce.OrderRef;
import com.shoppilot.commerce.TenantId;
import com.shoppilot.commerce.tck.CommerceGatewayContractTest;
import org.junit.jupiter.api.BeforeEach;

class ShopifyCommerceGatewayContractTest extends CommerceGatewayContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FakeShopifyTransport transport = new FakeShopifyTransport();
    private final ShopifyCommerceGateway commerce = new ShopifyCommerceGateway(transport, mapper);
    private final TenantId tenant = new TenantId("demo.myshopify.com");
    private final TenantId otherTenant = new TenantId("other.myshopify.com");
    private final OrderRef paidOrder = new OrderRef("#1001");
    private final OrderRef uncapturedOrder = new OrderRef("#1002");

    @BeforeEach
    void seedOrders() throws Exception {
        transport.seedOrder(tenant, (ObjectNode) mapper.readTree("""
                {
                  "id": "gid://shopify/Order/1001",
                  "name": "#1001",
                  "displayFinancialStatus": "PAID",
                  "displayFulfillmentStatus": "FULFILLED",
                  "createdAt": "2026-11-24T00:00:00Z",
                  "processedAt": "2026-11-25T00:00:00Z",
                  "totalPriceSet": {"shopMoney": {"amount": "140.00", "currencyCode": "CNY"}},
                  "totalRefundedSet": {"shopMoney": {"amount": "0.00", "currencyCode": "CNY"}},
                  "totalRefundedShippingSet": {"shopMoney": {"amount": "0.00", "currencyCode": "CNY"}},
                  "shippingLines": {"nodes": [
                    {"originalPriceSet": {"shopMoney": {"amount": "10.00", "currencyCode": "CNY"}}}
                  ]},
                  "lineItems": {"nodes": [
                    {
                      "id": "gid://shopify/LineItem/1",
                      "sku": "SKU-A",
                      "title": "A",
                      "quantity": 2,
                      "refundableQuantity": 2,
                      "discountedUnitPriceSet": {"shopMoney": {"amount": "50.00", "currencyCode": "CNY"}},
                      "originalUnitPriceSet": {"shopMoney": {"amount": "50.00", "currencyCode": "CNY"}}
                    },
                    {
                      "id": "gid://shopify/LineItem/2",
                      "sku": "SKU-B",
                      "title": "B",
                      "quantity": 1,
                      "refundableQuantity": 1,
                      "discountedUnitPriceSet": {"shopMoney": {"amount": "30.00", "currencyCode": "CNY"}},
                      "originalUnitPriceSet": {"shopMoney": {"amount": "30.00", "currencyCode": "CNY"}}
                    }
                  ]}
                }
                """));
        transport.seedOrder(tenant, (ObjectNode) mapper.readTree("""
                {
                  "id": "gid://shopify/Order/1002",
                  "name": "#1002",
                  "displayFinancialStatus": "AUTHORIZED",
                  "displayFulfillmentStatus": "UNFULFILLED",
                  "createdAt": "2026-11-24T00:00:00Z",
                  "processedAt": "2026-11-25T00:00:00Z",
                  "totalPriceSet": {"shopMoney": {"amount": "50.00", "currencyCode": "CNY"}},
                  "totalRefundedSet": {"shopMoney": {"amount": "0.00", "currencyCode": "CNY"}},
                  "totalRefundedShippingSet": {"shopMoney": {"amount": "0.00", "currencyCode": "CNY"}},
                  "shippingLines": {"nodes": []},
                  "lineItems": {"nodes": [
                    {
                      "id": "gid://shopify/LineItem/1",
                      "sku": "SKU-A",
                      "title": "A",
                      "quantity": 1,
                      "refundableQuantity": 1,
                      "discountedUnitPriceSet": {"shopMoney": {"amount": "50.00", "currencyCode": "CNY"}},
                      "originalUnitPriceSet": {"shopMoney": {"amount": "50.00", "currencyCode": "CNY"}}
                    }
                  ]}
                }
                """));
    }

    @Override
    protected CommerceGateway gateway() {
        return commerce;
    }

    @Override
    protected TenantId tenant() {
        return tenant;
    }

    @Override
    protected TenantId otherTenant() {
        return otherTenant;
    }

    @Override
    protected OrderRef paidOrder() {
        return paidOrder;
    }

    @Override
    protected OrderRef uncapturedOrder() {
        return uncapturedOrder;
    }

    @Override
    protected String lineItemId() {
        return "gid://shopify/LineItem/1";
    }

    @Override
    protected Money lineItemUnitPrice() {
        return Money.of(5000, "CNY");
    }

    @Override
    protected Money shipping() {
        return Money.of(1000, "CNY");
    }

    @org.junit.jupiter.api.Test
    void shippingRefundIsNotOfferedTwice() {
        var first = CommerceResults.unwrap(
                commerce.createRefund(tenant, intent(1, true), key("ship-once")));

        org.assertj.core.api.Assertions.assertThat(first.amount()).isEqualTo(Money.of(6000, "CNY"));
        org.assertj.core.api.Assertions.assertThat(
                        errorOf(commerce.quoteRefund(tenant, intent(1, true))))
                .isEqualTo(com.shoppilot.commerce.CommerceError.STATE_NOT_ALLOWED);
    }

    @org.junit.jupiter.api.Test
    void missingRefundTotalFailsClosed() {
        transport.omitRefundTotalOnNextCreate();

        org.assertj.core.api.Assertions.assertThat(
                        errorOf(commerce.createRefund(tenant, intent(1, false), key("missing-total"))))
                .isEqualTo(com.shoppilot.commerce.CommerceError.INTERNAL);
    }
}
