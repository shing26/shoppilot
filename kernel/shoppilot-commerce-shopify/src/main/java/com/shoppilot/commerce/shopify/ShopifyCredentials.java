package com.shoppilot.commerce.shopify;

import com.shoppilot.commerce.TenantId;

/**
 * 按店铺取 access token。正式实现应接 OAuth/密钥管理，不把 token 写进代码。
 */
@FunctionalInterface
public interface ShopifyCredentials {

    String accessToken(TenantId tenant);
}
