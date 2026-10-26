package com.shoppilot.gateway.web;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

/**
 * 买家“我的订单”只读入口（ADR 0075）。
 *
 * <p>身份只从已验签的 {@link TenantContext} 取；请求参数里的买家号一律不读。
 * 内部 token 与租户/买家头由网关在服务端补，浏览器永远不接触 biz-mock。
 */
@RestController
@RequestMapping("/api/v1/support/orders")
public class BuyerOrdersController {

    private final HttpClient http;
    private final GatewayProperties properties;
    private final ApiErrorWriter errors;

    public BuyerOrdersController(HttpClient http, GatewayProperties properties, ApiErrorWriter errors) {
        this.http = http;
        this.properties = properties;
        this.errors = errors;
    }

    @GetMapping("/mine")
    public ResponseEntity<String> mine() {
        TenantContext.Identity identity = TenantContext.current();
        GatewayProperties.BizMock config = properties.bizmock();
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/orders/mine"))
                .timeout(config.readTimeout())
                .header("X-Internal-Token", config.internalToken())
                .header("X-Tenant-Id", identity.tenantId())
                .header("X-Customer-Id", identity.customerId() == null ? "" : identity.customerId())
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response.body());
            }
            return errors.entity(502, ApiError.DOWNSTREAM_UNREACHABLE,
                    "业务系统返回 " + response.statusCode());
        } catch (HttpTimeoutException timeout) {
            return errors.entity(504, ApiError.DOWNSTREAM_UNREACHABLE, "业务系统响应超时");
        } catch (Exception failure) {
            return errors.entity(502, ApiError.DOWNSTREAM_UNREACHABLE, "业务系统不可用");
        }
    }
}
