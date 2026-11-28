package com.shoppilot.commerce.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.shoppilot.commerce.TenantId;

import java.util.Map;

/**
 * Shopify Admin GraphQL 的唯一 IO 缝。适配器只依赖它，测试用内存实现替换。
 */
public interface ShopifyGraphQlTransport {

    Response execute(TenantId tenant, String query, Map<String, Object> variables);

    record Response(int status, JsonNode body) {
        public Response {
            if (status < 0) {
                throw new IllegalArgumentException("status must be >= 0");
            }
            body = body == null ? JsonNodeFactory.instance.objectNode() : body;
        }
    }
}
