package com.shoppilot.gateway.channel;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 票 225 / ADR 0087：出站 SSRF 的默认拒绝、allowlist 覆盖与逐跳复检。 */
class EgressPolicyTest {

    private HttpServer server;
    private String base;

    private EgressPolicy policy(String allowlist) {
        return new EgressPolicy(allowlist, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/to-metadata", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/to-private", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://10.0.0.8/hook");
            exchange.sendResponseHeaders(308, -1);
            exchange.close();
        });
        server.createContext("/found", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("默认拒绝 loopback、RFC1918、link-local、元数据与 IPv6 loopback")
    void defaultPolicyBlocksPrivateTargets() {
        EgressPolicy policy = policy("");

        assertThat(policy.check(URI.create("http://127.0.0.1/hook")).allowed()).isFalse();
        assertThat(policy.check(URI.create("http://10.1.2.3/hook")).allowed()).isFalse();
        assertThat(policy.check(URI.create("http://192.168.1.10/hook")).allowed()).isFalse();
        assertThat(policy.check(URI.create("http://169.254.169.254/latest/meta-data")).allowed()).isFalse();
        assertThat(policy.check(URI.create("http://[::1]/hook")).allowed()).isFalse();
        assertThat(policy.check(URI.create("http://100.64.0.1/hook")).allowed()).isFalse();
        assertThat(policy.check(URI.create("http://192.0.2.1/hook")).allowed()).isFalse();
        assertThat(policy.check(URI.create("http://198.51.100.1/hook")).allowed()).isFalse();
        assertThat(policy.check(URI.create("http://203.0.113.1/hook")).allowed()).isFalse();
        assertThat(policy.check(URI.create("http://[2001:db8::1]/hook")).allowed()).isFalse();
        assertThat(policy.check(URI.create("ftp://example.com/hook")).allowed()).isFalse();
        assertThat(policy.check(URI.create("http://user:pass@example.com/hook")).allowed()).isFalse();
    }

    @Test
    @DisplayName("allowlist 可放行显式登记的回环 host，但元数据地址始终拒绝")
    void allowlistIsAnExplicitOperatorOverride() {
        EgressPolicy policy = policy("127.0.0.1");

        assertThat(policy.check(URI.create("http://127.0.0.1:1234/hook")).allowed()).isTrue();
        assertThat(policy.check(URI.create("http://169.254.169.254/latest/meta-data")).allowed()).isFalse();
    }

    @Test
    @DisplayName("公网字面量地址可投递，不依赖外网 DNS")
    void publicLiteralAddressIsAllowed() {
        assertThat(policy("").check(URI.create("https://1.1.1.1/hook")).allowed()).isTrue();
    }

    @Test
    @DisplayName("307/308 逐跳复检：跳转到元数据或私网被拒")
    void redirectsAreRevalidatedHopByHop() {
        EgressPolicy policy = policy("127.0.0.1");

        assertThatThrownBy(() -> policy.post(URI.create(base + "/to-metadata"), "{}", Map.of()))
                .isInstanceOf(EgressPolicy.DeniedException.class);
        assertThatThrownBy(() -> policy.post(URI.create(base + "/to-private"), "{}", Map.of()))
                .isInstanceOf(EgressPolicy.DeniedException.class);
    }

    @Test
    @DisplayName("302 不允许跟随：POST 被改成 GET 的重定向直接拒绝")
    void methodChangingRedirectIsRejected() {
        EgressPolicy policy = policy("127.0.0.1");

        assertThatThrownBy(() -> policy.post(URI.create(base + "/found"), "{}", Map.of()))
                .isInstanceOf(EgressPolicy.DeniedException.class)
                .hasMessageContaining("不允许的重定向");
    }

    @Test
    @DisplayName("allowlist 内的本地端点真能收到一次 POST")
    void allowlistedLocalEndpointReceivesPost() throws Exception {
        EgressPolicy policy = policy("127.0.0.1");

        HttpResponse<String> response = policy.post(URI.create(base + "/ok"), "{\"probe\":true}",
                Map.of("Content-Type", "application/json"));

        assertThat(response.statusCode()).isEqualTo(200);
    }
}
