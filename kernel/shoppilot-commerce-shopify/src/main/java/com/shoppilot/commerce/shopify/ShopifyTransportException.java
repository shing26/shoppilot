package com.shoppilot.commerce.shopify;

/**
 * 传输层失败（连接中断、超时、序列化失败）。适配器会把它映射成可重试的 UNAVAILABLE。
 */
public final class ShopifyTransportException extends RuntimeException {

    public ShopifyTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
