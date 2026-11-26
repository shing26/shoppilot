package com.shoppilot.commerce.shopify;

/**
 * 固定查询文本。operation name 是测试替身的分发依据，也是日志里可读的坐标。
 */
final class ShopifyQueries {

    private ShopifyQueries() {
    }

    private static final String ORDER_FIELDS = """
            fragment OrderFields on Order {
              id
              name
              displayFinancialStatus
              displayFulfillmentStatus
              createdAt
              processedAt
              totalPriceSet { shopMoney { amount currencyCode } }
              totalRefundedSet { shopMoney { amount currencyCode } }
              totalRefundedShippingSet { shopMoney { amount currencyCode } }
              shippingLines(first: 10) {
                nodes { originalPriceSet { shopMoney { amount currencyCode } } }
              }
              lineItems(first: 100) {
                nodes {
                  id
                  sku
                  title
                  quantity
                  refundableQuantity
                  discountedUnitPriceSet { shopMoney { amount currencyCode } }
                  originalUnitPriceSet { shopMoney { amount currencyCode } }
                }
              }
            }
            """;

    static final String ORDER_BY_NAME = """
            query OrderByName($query: String!) {
              orders(first: 1, query: $query) {
                nodes { ...OrderFields }
              }
            }
            """ + ORDER_FIELDS;

    static final String ORDER_BY_ID = """
            query OrderById($id: ID!) {
              order(id: $id) { ...OrderFields }
            }
            """ + ORDER_FIELDS;

    static final String REFUND_CREATE = """
            mutation RefundCreate($input: RefundInput!, $key: String!) {
              refundCreate(input: $input) @idempotent(key: $key) {
                refund {
                  id
                  totalRefundedSet { shopMoney { amount currencyCode } }
                  transactions(first: 10) {
                    nodes {
                      status
                      kind
                      amountSet { shopMoney { amount currencyCode } }
                    }
                  }
                }
                userErrors { field message }
              }
            }
            """;

    static final String REFUND_STATUS = """
            query RefundStatus($id: ID!) {
              refund(id: $id) {
                id
                totalRefundedSet { shopMoney { amount currencyCode } }
                transactions(first: 10) {
                  nodes { status kind }
                }
              }
            }
            """;
}
