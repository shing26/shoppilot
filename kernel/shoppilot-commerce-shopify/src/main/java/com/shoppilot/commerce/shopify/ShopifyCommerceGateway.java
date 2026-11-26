package com.shoppilot.commerce.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.commerce.CommerceError;
import com.shoppilot.commerce.CommerceGateway;
import com.shoppilot.commerce.CommerceResult;
import com.shoppilot.commerce.CommerceResults;
import com.shoppilot.commerce.FinancialStatus;
import com.shoppilot.commerce.FulfillmentStatus;
import com.shoppilot.commerce.IdempotencyKey;
import com.shoppilot.commerce.LineItem;
import com.shoppilot.commerce.Money;
import com.shoppilot.commerce.OrderRef;
import com.shoppilot.commerce.OrderSnapshot;
import com.shoppilot.commerce.RefundIntent;
import com.shoppilot.commerce.RefundQuote;
import com.shoppilot.commerce.RefundQuoter;
import com.shoppilot.commerce.RefundReceipt;
import com.shoppilot.commerce.RefundStatus;
import com.shoppilot.commerce.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shopify Admin GraphQL 适配器。
 *
 * <p>边界：只做订单读取、报价、`refundCreate`、退款状态读取；退货/换货/restock、
 * 关税、多币种、Store Credit 不在本草案范围内。
 */
public final class ShopifyCommerceGateway implements CommerceGateway {

    private final ShopifyGraphQlTransport transport;
    private final ObjectMapper mapper;

    public ShopifyCommerceGateway(ShopifyGraphQlTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    public CommerceResult<OrderSnapshot> getOrder(TenantId tenant, OrderRef order) {
        String ref = order.value();
        boolean byId = ref.startsWith("gid://shopify/Order/");
        String query = byId ? ShopifyQueries.ORDER_BY_ID : ShopifyQueries.ORDER_BY_NAME;
        Map<String, Object> variables = byId
                ? Map.of("id", ref)
                : Map.of("query", "name:" + ref);

        var dataResult = execute(tenant, query, variables);
        if (!dataResult.isOk()) {
            return CommerceResults.copyErr(dataResult);
        }
        JsonNode data = CommerceResults.unwrap(dataResult);
        JsonNode node = byId
                ? data.path("order")
                : data.path("orders").path("nodes").path(0);
        if (node.isMissingNode() || node.isNull()) {
            return CommerceResult.err(CommerceError.NOT_FOUND, "order not found");
        }
        try {
            return CommerceResult.ok(mapOrder(node));
        } catch (RuntimeException e) {
            return CommerceResult.err(CommerceError.INTERNAL,
                    "malformed Shopify order payload: " + e.getMessage());
        }
    }

    @Override
    public CommerceResult<RefundQuote> quoteRefund(TenantId tenant, RefundIntent intent) {
        var orderResult = getOrder(tenant, intent.order());
        if (!orderResult.isOk()) {
            return CommerceResults.copyErr(orderResult);
        }
        return RefundQuoter.quote(CommerceResults.unwrap(orderResult), intent);
    }

    @Override
    public CommerceResult<RefundReceipt> createRefund(
            TenantId tenant, RefundIntent intent, IdempotencyKey key) {
        var orderResult = getOrder(tenant, intent.order());
        if (!orderResult.isOk()) {
            return CommerceResults.copyErr(orderResult);
        }
        OrderSnapshot order = CommerceResults.unwrap(orderResult);
        var quoteResult = RefundQuoter.quote(order, intent);
        if (!quoteResult.isOk()) {
            return CommerceResults.copyErr(quoteResult);
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("orderId", order.commerceOrderId());
        input.put("note", intent.reason());
        input.put("notify", false);
        if (!intent.lines().isEmpty()) {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (var line : intent.lines()) {
                lines.add(Map.of(
                        "lineItemId", line.lineItemId(),
                        "quantity", line.quantity(),
                        "restockType", "NO_RESTOCK"));
            }
            input.put("refundLineItems", lines);
        }
        if (intent.refundShipping()) {
            input.put("shipping", Map.of("fullRefund", true));
        }

        var dataResult = execute(tenant, ShopifyQueries.REFUND_CREATE,
                Map.of("input", input, "key", key.value()));
        if (!dataResult.isOk()) {
            return CommerceResults.copyErr(dataResult);
        }
        JsonNode payload = CommerceResults.unwrap(dataResult).path("refundCreate");
        JsonNode userErrors = payload.path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String message = userErrors.path(0).path("message").asText("Shopify rejected the refund");
            return CommerceResult.err(CommerceError.INVALID_REQUEST, message);
        }
        JsonNode refund = payload.path("refund");
        if (refund.isMissingNode() || refund.isNull()) {
            return CommerceResult.err(CommerceError.INTERNAL, "Shopify returned no refund");
        }

        try {
            RefundStatus status = mapTransactions(refund.path("transactions").path("nodes"));
            if (status == RefundStatus.FAILED) {
                return CommerceResult.err(CommerceError.STATE_NOT_ALLOWED,
                        "Shopify refund transaction failed");
            }
            Money amount = ShopifyJson.money(refund.path("totalRefundedSet"));
            return CommerceResult.ok(new RefundReceipt(
                    refund.path("id").asText(), status, amount, key, Instant.now()));
        } catch (RuntimeException e) {
            return CommerceResult.err(CommerceError.INTERNAL,
                    "malformed Shopify refund payload: " + e.getMessage());
        }
    }

    @Override
    public CommerceResult<RefundStatus> getRefundStatus(TenantId tenant, String commerceRefundId) {
        var dataResult = execute(tenant, ShopifyQueries.REFUND_STATUS,
                Map.of("id", commerceRefundId));
        if (!dataResult.isOk()) {
            return CommerceResults.copyErr(dataResult);
        }
        JsonNode refund = CommerceResults.unwrap(dataResult).path("refund");
        if (refund.isMissingNode() || refund.isNull()) {
            return CommerceResult.err(CommerceError.NOT_FOUND, "refund not found");
        }
        return CommerceResult.ok(mapTransactions(refund.path("transactions").path("nodes")));
    }

    private CommerceResult<JsonNode> execute(
            TenantId tenant, String query, Map<String, Object> variables) {
        try {
            return interpret(transport.execute(tenant, query, variables));
        } catch (ShopifyTransportException e) {
            return CommerceResult.err(CommerceError.UNAVAILABLE, e.getMessage());
        } catch (RuntimeException e) {
            return CommerceResult.err(CommerceError.INTERNAL, e.getMessage());
        }
    }

    private static CommerceResult<JsonNode> interpret(ShopifyGraphQlTransport.Response response) {
        int status = response.status();
        if (status == 429) {
            return CommerceResult.err(CommerceError.THROTTLED, "Shopify throttled the request");
        }
        if (status == 401 || status == 403) {
            return CommerceResult.err(CommerceError.INTERNAL, "Shopify authentication failed");
        }
        if (status == 404) {
            return CommerceResult.err(CommerceError.NOT_FOUND, "Shopify resource not found");
        }
        if (status >= 500) {
            return CommerceResult.err(CommerceError.UNAVAILABLE, "Shopify returned " + status);
        }

        JsonNode body = response.body();
        JsonNode errors = body.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            JsonNode first = errors.path(0);
            String code = first.path("extensions").path("code").asText("");
            String message = first.path("message").asText("Shopify GraphQL error");
            if ("THROTTLED".equals(code)) {
                return CommerceResult.err(CommerceError.THROTTLED, message);
            }
            if ("NOT_FOUND".equals(code)) {
                return CommerceResult.err(CommerceError.NOT_FOUND, message);
            }
            return CommerceResult.err(CommerceError.INTERNAL, message);
        }

        JsonNode data = body.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return CommerceResult.err(CommerceError.INTERNAL, "Shopify response has no data");
        }
        return CommerceResult.ok(data);
    }

    private static OrderSnapshot mapOrder(JsonNode node) {
        String gid = ShopifyJson.text(node, "id");
        if (gid.isBlank()) {
            throw new IllegalArgumentException("missing order id");
        }
        String name = ShopifyJson.text(node, "name");
        Money total = ShopifyJson.money(node.path("totalPriceSet"));
        Money refunded = ShopifyJson.money(node.path("totalRefundedSet"));
        Money refundedShipping = ShopifyJson.money(node.path("totalRefundedShippingSet"));
        Money shipping = ShopifyJson.sumShipping(
                node.path("shippingLines").path("nodes"), total.currency());

        List<LineItem> items = new ArrayList<>();
        for (JsonNode item : node.path("lineItems").path("nodes")) {
            int quantity = item.path("quantity").asInt();
            int refundable = item.path("refundableQuantity").asInt(quantity);
            Money unit = ShopifyJson.moneyOrZero(
                    item.path("discountedUnitPriceSet"), total.currency());
            if (unit.isZero()) {
                unit = ShopifyJson.moneyOrZero(item.path("originalUnitPriceSet"), total.currency());
            }
            items.add(new LineItem(
                    ShopifyJson.text(item, "id"),
                    ShopifyJson.text(item, "sku"),
                    ShopifyJson.text(item, "title"),
                    quantity,
                    quantity - refundable,
                    unit));
        }

        return new OrderSnapshot(
                new OrderRef(name.isBlank() ? gid : name),
                gid,
                total,
                refunded,
                shipping,
                refundedShipping.isPositive(),
                mapFinancial(ShopifyJson.text(node, "displayFinancialStatus")),
                mapFulfillment(ShopifyJson.text(node, "displayFulfillmentStatus")),
                items,
                ShopifyJson.instant(node, "createdAt"),
                ShopifyJson.instantOrNull(node, "processedAt"));
    }

    private static FinancialStatus mapFinancial(String value) {
        return switch (value) {
            case "PENDING" -> FinancialStatus.PENDING;
            case "AUTHORIZED" -> FinancialStatus.AUTHORIZED;
            case "PARTIALLY_PAID" -> FinancialStatus.PARTIALLY_PAID;
            case "PAID" -> FinancialStatus.PAID;
            case "PARTIALLY_REFUNDED" -> FinancialStatus.PARTIALLY_REFUNDED;
            case "REFUNDED" -> FinancialStatus.REFUNDED;
            case "VOIDED" -> FinancialStatus.VOIDED;
            case "EXPIRED" -> FinancialStatus.EXPIRED;
            default -> FinancialStatus.UNKNOWN;
        };
    }

    private static FulfillmentStatus mapFulfillment(String value) {
        return switch (value) {
            case "UNFULFILLED", "OPEN" -> FulfillmentStatus.UNFULFILLED;
            case "PARTIALLY_FULFILLED" -> FulfillmentStatus.PARTIALLY_FULFILLED;
            case "FULFILLED", "FULFILLMENT_NOT_REQUIRED" -> FulfillmentStatus.FULFILLED;
            case "RESTOCKED" -> FulfillmentStatus.RESTOCKED;
            case "IN_PROGRESS", "PENDING_FULFILLMENT" -> FulfillmentStatus.IN_PROGRESS;
            case "ON_HOLD" -> FulfillmentStatus.ON_HOLD;
            case "SCHEDULED" -> FulfillmentStatus.SCHEDULED;
            default -> FulfillmentStatus.UNKNOWN;
        };
    }

    private static RefundStatus mapTransactions(JsonNode nodes) {
        boolean settled = false;
        boolean failed = false;
        if (nodes != null && nodes.isArray()) {
            for (JsonNode node : nodes) {
                if (!"REFUND".equals(ShopifyJson.text(node, "kind"))) {
                    continue;
                }
                String status = ShopifyJson.text(node, "status");
                if ("SUCCESS".equals(status)) {
                    settled = true;
                } else if ("FAILURE".equals(status) || "ERROR".equals(status)) {
                    failed = true;
                }
            }
        }
        if (failed) {
            return RefundStatus.FAILED;
        }
        return settled ? RefundStatus.SETTLED : RefundStatus.SUBMITTED;
    }
}
