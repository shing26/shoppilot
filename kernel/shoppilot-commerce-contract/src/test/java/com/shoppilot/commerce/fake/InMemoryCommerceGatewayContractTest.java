package com.shoppilot.commerce.fake;

import com.shoppilot.commerce.CommerceGateway;
import com.shoppilot.commerce.FinancialStatus;
import com.shoppilot.commerce.FulfillmentStatus;
import com.shoppilot.commerce.LineItem;
import com.shoppilot.commerce.Money;
import com.shoppilot.commerce.OrderRef;
import com.shoppilot.commerce.OrderSnapshot;
import com.shoppilot.commerce.TenantId;
import com.shoppilot.commerce.tck.CommerceGatewayContractTest;
import org.junit.jupiter.api.BeforeEach;

import java.time.Instant;
import java.util.List;

class InMemoryCommerceGatewayContractTest extends CommerceGatewayContractTest {

    private final InMemoryCommerceGateway commerce = new InMemoryCommerceGateway();
    private final TenantId tenant = new TenantId("demo.myshopify.com");
    private final TenantId otherTenant = new TenantId("other.myshopify.com");
    private final OrderRef order = new OrderRef("#1001");
    private final OrderRef uncapturedOrder = new OrderRef("#1002");
    private final Money unitPrice = Money.of(5000, "CNY");
    private final Money shipping = Money.of(1000, "CNY");

    @BeforeEach
    void seedOrder() {
        commerce.seedOrder(tenant, new OrderSnapshot(
                order,
                "gid://shopify/Order/1001",
                Money.of(14000, "CNY"),
                Money.zero("CNY"),
                shipping,
                false,
                FinancialStatus.PAID,
                FulfillmentStatus.FULFILLED,
                List.of(
                        new LineItem("line-a", "SKU-A", "A", 2, 0, unitPrice),
                        new LineItem("line-b", "SKU-B", "B", 1, 0, Money.of(3000, "CNY"))),
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600)));
        commerce.seedOrder(tenant, new OrderSnapshot(
                uncapturedOrder,
                "gid://shopify/Order/1002",
                Money.of(5000, "CNY"),
                Money.zero("CNY"),
                Money.zero("CNY"),
                false,
                FinancialStatus.AUTHORIZED,
                FulfillmentStatus.UNFULFILLED,
                List.of(new LineItem("line-a", "SKU-A", "A", 1, 0, unitPrice)),
                Instant.now().minusSeconds(3600),
                null));
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
        return order;
    }

    @Override
    protected OrderRef uncapturedOrder() {
        return uncapturedOrder;
    }

    @Override
    protected String lineItemId() {
        return "line-a";
    }

    @Override
    protected Money lineItemUnitPrice() {
        return unitPrice;
    }

    @Override
    protected Money shipping() {
        return shipping;
    }
}
