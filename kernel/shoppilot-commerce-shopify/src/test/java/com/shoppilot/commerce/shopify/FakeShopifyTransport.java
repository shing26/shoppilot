package com.shoppilot.commerce.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppilot.commerce.TenantId;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 只实现适配器用到的四个 GraphQL operation 的内存替身。
 *
 * <p>它不是 Shopify 的完整模拟器：不模拟折扣、税、币种换算、退货、限流与 webhook。
 * 它证明适配器的查询/解析/幂等映射，不证明 Shopify 真会接受这些查询——后者靠 live smoke。
 */
final class FakeShopifyTransport implements ShopifyGraphQlTransport {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ObjectNode> ordersByName = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> ordersById = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> refundsById = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> inputsByKey = new ConcurrentHashMap<>();
    private final Map<String, String> refundIdByKey = new ConcurrentHashMap<>();
    private final AtomicInteger refundSequence = new AtomicInteger();
    private volatile boolean omitRefundTotal;
    private volatile boolean unsettledCreationShape;
    private volatile BigDecimal reportedTotalOverride;
    private volatile ObjectNode lastRefundInput;
    private volatile Integer nextHttpStatus;
    private volatile String nextGraphQlErrorCode;

    void seedOrder(TenantId tenant, ObjectNode order) {
        ordersByName.put(tenant.value() + "|" + order.path("name").asText(), order);
        ordersById.put(tenant.value() + "|" + order.path("id").asText(), order);
    }

    void markSettled(TenantId tenant, String refundId) {
        setTransactionStatus(tenant, refundId, "SUCCESS");
    }

    void markFailed(TenantId tenant, String refundId) {
        setTransactionStatus(tenant, refundId, "FAILURE");
    }

    void omitRefundTotalOnNextCreate() {
        omitRefundTotal = true;
    }

    void useUnsettledCreationShapeOnNextCreate() {
        unsettledCreationShape = true;
    }

    void reportWrongRefundTotalOnNextCreate(BigDecimal reportedTotal) {
        reportedTotalOverride = reportedTotal;
    }

    ObjectNode lastRefundInput() {
        return lastRefundInput;
    }

    void failNextWithHttp(int status) {
        nextHttpStatus = status;
    }

    void failNextWithGraphQlError(String code) {
        nextGraphQlErrorCode = code;
    }

    @Override
    public Response execute(TenantId tenant, String query, Map<String, Object> variables) {
        if (nextHttpStatus != null) {
            int status = nextHttpStatus;
            nextHttpStatus = null;
            return new Response(status, mapper.createObjectNode());
        }
        if (nextGraphQlErrorCode != null) {
            String code = nextGraphQlErrorCode;
            nextGraphQlErrorCode = null;
            return new Response(200, errorBody(code, "throttled"));
        }
        if (query.contains("OrderByName")) {
            return orderByName(tenant, variables);
        }
        if (query.contains("OrderById")) {
            return orderById(tenant, variables);
        }
        if (query.contains("RefundCreate")) {
            return refundCreate(tenant, variables);
        }
        if (query.contains("OrderTransactions")) {
            return orderTransactions(tenant, variables);
        }
        if (query.contains("RefundStatus")) {
            return refundStatus(tenant, variables);
        }
        return new Response(200, errorBody("UNKNOWN_OPERATION", "unknown query"));
    }

    private Response orderByName(TenantId tenant, Map<String, Object> variables) {
        String query = String.valueOf(variables.get("query"));
        String name = query.startsWith("name:") ? query.substring("name:".length()) : query;
        ObjectNode order = ordersByName.get(tenant.value() + "|" + name);

        ObjectNode root = mapper.createObjectNode();
        var nodes = root.putObject("data").putObject("orders").putArray("nodes");
        if (order != null) {
            nodes.add(order);
        }
        return new Response(200, root);
    }

    private Response orderById(TenantId tenant, Map<String, Object> variables) {
        String id = String.valueOf(variables.get("id"));
        ObjectNode order = ordersById.get(tenant.value() + "|" + id);

        ObjectNode root = mapper.createObjectNode();
        if (order == null) {
            root.putObject("data").putNull("order");
        } else {
            root.putObject("data").set("order", order);
        }
        return new Response(200, root);
    }

    private Response refundCreate(TenantId tenant, Map<String, Object> variables) {
        String key = String.valueOf(variables.get("key"));
        String keyKey = tenant.value() + "|" + key;
        ObjectNode input = mapper.valueToTree(variables.get("input"));
        lastRefundInput = input;

        ObjectNode priorInput = inputsByKey.get(keyKey);
        if (priorInput != null) {
            if (priorInput.equals(input)) {
                ObjectNode refund = refundsById.get(tenant.value() + "|" + refundIdByKey.get(keyKey));
                return refundPayload(refund);
            }
            return userError("IDEMPOTENCY_KEY_REUSED", "idempotency key reused with a different input");
        }

        String orderId = input.path("orderId").asText();
        ObjectNode order = ordersById.get(tenant.value() + "|" + orderId);
        if (order == null) {
            return userError("NOT_FOUND", "order not found");
        }

        JsonNode transactions = input.path("transactions");
        if (!transactions.isArray() || transactions.isEmpty()
                || !"REFUND".equals(transactions.path(0).path("kind").asText())) {
            return userError("NO_TRANSACTION", "a REFUND transaction is required");
        }

        BigDecimal amount = BigDecimal.ZERO;
        for (JsonNode line : input.path("refundLineItems")) {
            String lineItemId = line.path("lineItemId").asText();
            int quantity = line.path("quantity").asInt();
            ObjectNode item = findLineItem(order, lineItemId);
            if (item == null) {
                return userError("INVALID", "unknown line item: " + lineItemId);
            }
            int refundable = item.path("refundableQuantity").asInt();
            if (quantity <= 0 || quantity > refundable) {
                return userError("INVALID", "quantity exceeds refundable amount for " + lineItemId);
            }
            amount = amount.add(money(item, "discountedUnitPriceSet").multiply(BigDecimal.valueOf(quantity)));
            item.put("refundableQuantity", refundable - quantity);
        }

        if (input.path("shipping").path("fullRefund").asBoolean(false)) {
            BigDecimal shipping = shippingTotal(order);
            amount = amount.add(shipping);
            setMoney(order, "totalRefundedShippingSet",
                    money(order, "totalRefundedShippingSet").add(shipping), currency(order));
        }

        BigDecimal total = money(order, "totalPriceSet");
        BigDecimal alreadyRefunded = money(order, "totalRefundedSet");
        if (alreadyRefunded.add(amount).compareTo(total) > 0) {
            return userError("INVALID", "refund exceeds order total");
        }

        String currency = currency(order);
        setMoney(order, "totalRefundedSet", alreadyRefunded.add(amount), currency);

        String refundId = "gid://shopify/Refund/" + refundSequence.incrementAndGet();
        ObjectNode refund = mapper.createObjectNode();
        refund.put("id", refundId);
        if (!omitRefundTotal) {
            BigDecimal reported = reportedTotalOverride != null
                    ? reportedTotalOverride
                    : (unsettledCreationShape ? BigDecimal.ZERO : amount);
            setMoney(refund, "totalRefundedSet", reported, currency);
        }
        if (!unsettledCreationShape) {
            ObjectNode transaction = refund.putObject("transactions")
                    .putArray("nodes")
                    .addObject();
            transaction.put("status", "PENDING");
            transaction.put("kind", "REFUND");
            setMoney(transaction, "amountSet", amount, currency);
        }
        unsettledCreationShape = false;
        reportedTotalOverride = null;

        refundsById.put(tenant.value() + "|" + refundId, refund);
        inputsByKey.put(keyKey, input);
        refundIdByKey.put(keyKey, refundId);
        return refundPayload(refund);
    }

    private Response orderTransactions(TenantId tenant, Map<String, Object> variables) {
        String id = String.valueOf(variables.get("id"));
        ObjectNode order = ordersById.get(tenant.value() + "|" + id);

        ObjectNode root = mapper.createObjectNode();
        ObjectNode data = root.putObject("data");
        if (order == null) {
            data.putNull("order");
        } else {
            ObjectNode view = data.putObject("order");
            view.put("id", id);
            view.set("transactions",
                    order.path("transactions").isArray()
                            ? order.path("transactions")
                            : mapper.createArrayNode());
        }
        return new Response(200, root);
    }

    private Response refundStatus(TenantId tenant, Map<String, Object> variables) {
        String id = String.valueOf(variables.get("id"));
        ObjectNode refund = refundsById.get(tenant.value() + "|" + id);

        ObjectNode root = mapper.createObjectNode();
        if (refund == null) {
            root.putObject("data").putNull("refund");
        } else {
            root.putObject("data").set("refund", refund);
        }
        return new Response(200, root);
    }

    private Response refundPayload(ObjectNode refund) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode payload = root.putObject("data").putObject("refundCreate");
        if (refund == null) {
            payload.putNull("refund");
        } else {
            payload.set("refund", refund);
        }
        payload.putArray("userErrors");
        return new Response(200, root);
    }

    private Response userError(String code, String message) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode payload = root.putObject("data").putObject("refundCreate");
        payload.putNull("refund");
        payload.putArray("userErrors").addObject()
                .put("field", "")
                .put("message", message)
                .put("code", code);
        return new Response(200, root);
    }

    private ObjectNode errorBody(String code, String message) {
        ObjectNode root = mapper.createObjectNode();
        root.putArray("errors").addObject()
                .put("message", message)
                .putObject("extensions")
                .put("code", code);
        return root;
    }

    private void setTransactionStatus(TenantId tenant, String refundId, String status) {
        ObjectNode refund = refundsById.get(tenant.value() + "|" + refundId);
        if (refund == null) {
            throw new IllegalArgumentException("unknown refund: " + refundId);
        }
        ObjectNode transaction = (ObjectNode) refund.path("transactions").path("nodes").path(0);
        transaction.put("status", status);
    }

    private static ObjectNode findLineItem(ObjectNode order, String id) {
        for (JsonNode item : order.path("lineItems").path("nodes")) {
            if (item.path("id").asText().equals(id)) {
                return (ObjectNode) item;
            }
        }
        return null;
    }

    private static BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.path(field).path("shopMoney").path("amount").asText());
    }

    private static BigDecimal shippingTotal(JsonNode order) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode line : order.path("shippingLines").path("nodes")) {
            total = total.add(new BigDecimal(
                    line.path("originalPriceSet").path("shopMoney").path("amount").asText()));
        }
        return total;
    }

    private static String currency(JsonNode order) {
        return order.path("totalPriceSet").path("shopMoney").path("currencyCode").asText();
    }

    private static void setMoney(ObjectNode node, String field, BigDecimal amount, String currency) {
        ObjectNode shopMoney = node.putObject(field).putObject("shopMoney");
        shopMoney.put("amount", amount.setScale(2).toPlainString());
        shopMoney.put("currencyCode", currency);
    }
}
