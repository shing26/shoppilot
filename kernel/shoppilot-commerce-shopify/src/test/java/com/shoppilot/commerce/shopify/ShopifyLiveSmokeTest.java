package com.shoppilot.commerce.shopify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.commerce.CommerceResult;
import com.shoppilot.commerce.OrderRef;
import com.shoppilot.commerce.TenantId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 需要真实开发商店的手动 smoke：不配环境变量时自动跳过（aborted，不是失败）。
 *
 * <pre>
 * SHOPIFY_SHOP=demo.myshopify.com
 * SHOPIFY_TOKEN=shpat_...
 * SHOPIFY_ORDER_NAME=#1001
 * </pre>
 */
class ShopifyLiveSmokeTest {

    @Test
    void readsConfiguredOrder() {
        String shop = System.getenv("SHOPIFY_SHOP");
        String token = System.getenv("SHOPIFY_TOKEN");
        assumeTrue(shop != null && !shop.isBlank() && token != null && !token.isBlank(),
                "set SHOPIFY_SHOP and SHOPIFY_TOKEN to run the live smoke");

        TenantId tenant = new TenantId(shop);
        String orderName = System.getenv().getOrDefault("SHOPIFY_ORDER_NAME", "#1001");
        var transport = new HttpShopifyGraphQlTransport(t -> {
            if (!t.equals(tenant)) {
                throw new ShopifyTransportException("unexpected tenant: " + t.value(), null);
            }
            return token;
        });
        var gateway = new ShopifyCommerceGateway(transport, new ObjectMapper());

        CommerceResult<?> result = gateway.getOrder(tenant, new OrderRef(orderName));
        assertThat(result).isInstanceOf(CommerceResult.Ok.class);
    }
}
