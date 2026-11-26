package com.shoppilot.commerce.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.commerce.TenantId;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 真实 HTTP 传输。固定 API 版本，按店铺拼 endpoint 与 access token。
 */
public final class HttpShopifyGraphQlTransport implements ShopifyGraphQlTransport {

    public static final String DEFAULT_API_VERSION = "2026-10";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final ShopifyCredentials credentials;
    private final String apiVersion;

    public HttpShopifyGraphQlTransport(ShopifyCredentials credentials) {
        this(credentials, new ObjectMapper(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), DEFAULT_API_VERSION);
    }

    public HttpShopifyGraphQlTransport(ShopifyCredentials credentials, ObjectMapper mapper) {
        this(credentials, mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), DEFAULT_API_VERSION);
    }

    public HttpShopifyGraphQlTransport(
            ShopifyCredentials credentials,
            ObjectMapper mapper,
            HttpClient http,
            String apiVersion) {
        this.credentials = credentials;
        this.mapper = mapper;
        this.http = http;
        this.apiVersion = apiVersion;
    }

    @Override
    public Response execute(TenantId tenant, String query, Map<String, Object> variables) {
        String shop = tenant.value()
                .replaceFirst("^https?://", "")
                .replaceAll("/+$", "");
        String token = credentials.accessToken(tenant);
        if (token == null || token.isBlank()) {
            throw new ShopifyTransportException("missing access token for " + shop, null);
        }

        var payload = mapper.createObjectNode();
        payload.put("query", query);
        payload.set("variables", mapper.valueToTree(variables));

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(
                            "https://" + shop + "/admin/api/" + apiVersion + "/graphql.json"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Shopify-Access-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
        } catch (IOException e) {
            throw new ShopifyTransportException("failed to serialize Shopify request", e);
        }

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.body());
            return new Response(response.statusCode(), body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShopifyTransportException("Shopify request interrupted", e);
        } catch (IOException e) {
            throw new ShopifyTransportException("Shopify request failed", e);
        }
    }
}
