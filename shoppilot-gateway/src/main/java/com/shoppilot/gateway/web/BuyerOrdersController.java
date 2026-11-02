package com.shoppilot.gateway.web;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.regex.Pattern;

/**
 * 买家“我的订单”只读入口（ADR 0075；round44 票 189 增详情与物流）。
 *
 * <p>身份只从已验签的 {@link TenantContext} 取；请求参数里的买家号一律不读。
 * 内部 token 与租户/买家头由网关在服务端补，浏览器永远不接触 biz-mock。
 *
 * <p>路径参数在拼进下游 URL 之前先过白名单正则。`URI.create(base + "/mine/" + orderNo)` 里
 * 一个含 `..` 的订单号会把请求带到我们没打算暴露的路径上；校验失败直接 404，与越权同形。
 */
@RestController
@RequestMapping("/api/v1/support/orders")
public class BuyerOrdersController {

    /** 订单号在种子数据与业务里都是短标识；白名单只留标识字符，顺带挡掉路径穿越。 */
    private static final Pattern ORDER_NO = Pattern.compile("[A-Za-z0-9_-]{1,32}");

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
        GatewayProperties.BizMock config = properties.bizmock();
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/orders/mine"))
                .timeout(config.readTimeout())
                .header("X-Internal-Token", config.internalToken())
                .header("X-Tenant-Id", TenantContext.tenantId())
                .header("X-Customer-Id", customerHeader())
                .GET()
                .build();
        return send(request);
    }

    @GetMapping("/mine/{orderNo}")
    public ResponseEntity<String> detail(@PathVariable String orderNo) {
        return forwardOrderRead(orderNo, "");
    }

    @GetMapping("/mine/{orderNo}/logistics")
    public ResponseEntity<String> logistics(@PathVariable String orderNo) {
        return forwardOrderRead(orderNo, "/logistics");
    }

    private ResponseEntity<String> forwardOrderRead(String orderNo, String suffix) {
        if (orderNo == null || !ORDER_NO.matcher(orderNo).matches()) {
            return ResponseEntity.notFound().build();
        }
        GatewayProperties.BizMock config = properties.bizmock();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(config.baseUrl() + "/api/orders/mine/" + orderNo + suffix))
                .timeout(config.readTimeout())
                .header("X-Internal-Token", config.internalToken())
                .header("X-Tenant-Id", TenantContext.tenantId())
                .header("X-Customer-Id", customerHeader())
                .GET()
                .build();
        return send(request);
    }

    private static String customerHeader() {
        TenantContext.Identity identity = TenantContext.current();
        return identity.customerId() == null ? "" : identity.customerId();
    }

    /**
     * 下游 404 原样传成 404 —— 越权与不存在必须同形，改写成 502 会让"这单存在但不是你的"
     * 从状态码上就露出来。其余非 2xx 仍是下游不可用。
     */
    private ResponseEntity<String> send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response.body());
            }
            if (response.statusCode() == 404) {
                return ResponseEntity.notFound().build();
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
