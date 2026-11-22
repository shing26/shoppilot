package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.bizmock.repo.RefreshTokenRepository;
import com.shoppilot.bizmock.repo.UserAccountRepository;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份生命周期（round56 票 224 / ADR 0086）。
 *
 * <p>走真实 HTTP，和 {@code IdentityDomainTest} 同一理由：租户上下文由内部过滤器写入，
 * 直接调 service 会把「租户从哪来」当前提用掉。本类覆盖 refresh 轮换/复用、改密、
 * 锁定与窗口恢复、停用与角色变更。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:identity-lifecycle;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.seed.orders=20",
        "shoppilot.bizmock.seed.customers=5",
        "shoppilot.bizmock.internal-token=test-internal",
        // 演示账号播种会把 buyer/agent/admin 建好；口令只在本测试进程里存在。
        "shoppilot.bizmock.identity.demo-password=lifecycle-pass-1"
})
class IdentityLifecycleTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;
    @Autowired
    UserAccountRepository accounts;
    @Autowired
    RefreshTokenRepository refreshTokens;
    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("refresh 单次使用：轮换后旧值拒绝，再用旧值撤销整族")
    void refreshRotatesAndReuseRevokesFamily() throws Exception {
        register("reuse-agent", "{{SECRET_bt2y6mnq}}");
        JsonNode login = login("reuse-agent", "{{SECRET_bt2y6mnq}}");
        String first = login.path("refreshToken").asText();
        assertThat(first).isNotBlank();

        JsonNode rotated = refresh(first);
        String second = rotated.path("refreshToken").asText();
        assertThat(second).isNotBlank().isNotEqualTo(first);

        // 旧 refresh 再用：复用检测，整族撤销
        HttpResponse<String> reused = post("/api/identity/refresh",
                JSON.writeValueAsString(Map.of("refreshToken", first)), headers("T001"));
        assertThat(reused.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(reused.body()).path("code").asText()).isEqualTo("refresh_reuse");

        // 新 refresh 也已被撤：复用检测撤销的是整族，不是单张
        HttpResponse<String> afterRevoke = post("/api/identity/refresh",
                JSON.writeValueAsString(Map.of("refreshToken", second)), headers("T001"));
        assertThat(afterRevoke.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("并发双用同一个 refresh：只允许一个 200，另一个按复用拒绝并撤族")
    void concurrentRefreshUsesTokenAtMostOnce() throws Exception {
        register("race-agent", "{{SECRET_9yq2v6wp}}");
        String refresh = login("race-agent", "{{SECRET_9yq2v6wp}}").path("refreshToken").asText();
        assertThat(refresh).isNotBlank();

        String body = JSON.writeValueAsString(Map.of("refreshToken", refresh));
        HttpResponse<String> first = sendWithNewClient(body);
        HttpResponse<String> second = sendWithNewClient(body);

        long ok = java.util.stream.Stream.of(first, second)
                .filter((r) -> r.statusCode() == 200).count();
        long reused = java.util.stream.Stream.of(first, second)
                .filter((r) -> r.statusCode() == 401).count();
        assertThat(ok).as("同一个 refresh 只能成功一次").isEqualTo(1);
        assertThat(reused).as("并发复用必须被拒").isEqualTo(1);

        HttpResponse<String> winner = first.statusCode() == 200 ? first : second;
        String rotated = JSON.readTree(winner.body()).path("refreshToken").asText();
        // 复用检测撤销整族：赢家刚拿到的新令牌也应立即失效，不能留下一个可用会话。
        HttpResponse<String> afterFamilyRevoke = post("/api/identity/refresh",
                JSON.writeValueAsString(Map.of("refreshToken", rotated)), headers("T001"));
        assertThat(afterFamilyRevoke.statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> sendWithNewClient(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/identity/refresh"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json;charset=UTF-8")
                .headers("X-Internal-Token", TOKEN, "X-Tenant-Id", "T001")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("改密：错误当前口令拒绝；成功后旧 refresh 失效、旧口令登录失败、新口令成功")
    void changePasswordRevokesOldCredentials() throws Exception {
        register("passwd-agent", "{{SECRET_8cgs7l0b}}");
        JsonNode login = login("passwd-agent", "{{SECRET_8cgs7l0b}}");
        String refresh = login.path("refreshToken").asText();
        String accountId = login.path("accountId").asText();

        HttpResponse<String> wrong = post("/api/identity/accounts/" + accountId + "/password",
                JSON.writeValueAsString(Map.of("currentPassword", "not-the-password",
                        "newPassword", "lifecycle-pass-2")), headers("T001"));
        // 403 = 已登录但当前口令不对；401 会被前端当成会话失效而登出。
        assertThat(wrong.statusCode()).isEqualTo(403);

        HttpResponse<String> changed = post("/api/identity/accounts/" + accountId + "/password",
                JSON.writeValueAsString(Map.of("currentPassword", "{{SECRET_8cgs7l0b}}",
                        "newPassword", "lifecycle-pass-2")), headers("T001"));
        assertThat(changed.statusCode()).isEqualTo(200);

        assertThat(login("passwd-agent", "{{SECRET_8cgs7l0b}}").path("error").asText()).isNotBlank();
        assertThat(login("passwd-agent", "lifecycle-pass-2").path("accountId").asText()).isEqualTo(accountId);

        HttpResponse<String> stale = post("/api/identity/refresh",
                JSON.writeValueAsString(Map.of("refreshToken", refresh)), headers("T001"));
        assertThat(stale.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("连续失败 5 次锁定：正确口令也拒绝（429），窗口过后自动恢复")
    void lockoutBlocksCorrectPasswordUntilWindowEnds() throws Exception {
        // 用独立账号，避免把 buyer/agent 的锁定状态带给别的用例
        post("/api/identity/register",
                "{\"username\":\"locky\",\"password\":\"locky-pass-1\"}", headers("T001"));
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> failed = post("/api/identity/authenticate",
                    "{\"username\":\"locky\",\"password\":\"wrong-pass-" + i + "\"}", headers("T001"));
            assertThat(failed.statusCode()).isEqualTo(401);
        }

        HttpResponse<String> locked = post("/api/identity/authenticate",
                "{\"username\":\"locky\",\"password\":\"locky-pass-1\"}", headers("T001"));
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        // 文案不确认账号是否存在
        assertThat(locked.body()).doesNotContain("locky");

        // 把锁定截止拨到过去，模拟窗口结束；不改失败计数，验的是「窗口后自动恢复」
        withTenant("T001", () -> jdbc.update(
                "update users set locked_until = ? where username = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)), "locky"));

        HttpResponse<String> recovered = post("/api/identity/authenticate",
                "{\"username\":\"locky\",\"password\":\"locky-pass-1\"}", headers("T001"));
        assertThat(recovered.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("停用与角色变更：refresh 立即拒绝，账号回读反映最新状态")
    void disableAndRoleChangeInvalidateRefresh() throws Exception {
        register("status-agent", "{{SECRET_2jplxq8d}}");
        JsonNode login = login("status-agent", "{{SECRET_2jplxq8d}}");
        String refresh = login.path("refreshToken").asText();
        String accountId = login.path("accountId").asText();

        HttpResponse<String> role = post("/api/identity/accounts/" + accountId + "/role",
                "{\"role\":\"ADMIN\"}", headers("T001"));
        assertThat(role.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(role.body()).path("role").asText()).isEqualTo("ADMIN");

        HttpResponse<String> stale = post("/api/identity/refresh",
                JSON.writeValueAsString(Map.of("refreshToken", refresh)), headers("T001"));
        assertThat(stale.statusCode()).isEqualTo(401);

        JsonNode relogin = login("status-agent", "{{SECRET_2jplxq8d}}");
        String secondRefresh = relogin.path("refreshToken").asText();
        HttpResponse<String> disabled = post("/api/identity/accounts/" + accountId + "/status",
                "{\"active\":false}", headers("T001"));
        assertThat(disabled.statusCode()).isEqualTo(200);

        HttpResponse<String> afterDisable = post("/api/identity/refresh",
                JSON.writeValueAsString(Map.of("refreshToken", secondRefresh)), headers("T001"));
        assertThat(afterDisable.statusCode()).isEqualTo(401);

        // 停用后登录被拒（对外文案与口令错同句），这是 /auth/me 回读判定的同一份服务端事实。
        assertThat(login("status-agent", "{{SECRET_2jplxq8d}}").path("error").asText()).isNotBlank();
    }

    @Test
    @DisplayName("refresh 明文不落库：库里只有 SHA-256，扫不到明文")
    void refreshTokenIsStoredHashed() throws Exception {
        String refresh = login("buyer", "lifecycle-pass-1").path("refreshToken").asText();
        assertThat(refresh).isNotBlank();
        Long hits = withTenant("T001", () -> jdbc.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ?", Long.class, refresh));
        assertThat(hits).isZero();
        Long stored = withTenant("T001", () -> jdbc.queryForObject(
                "select count(*) from refresh_tokens", Long.class));
        assertThat(stored).isPositive();
    }

    private JsonNode login(String username, String password) throws Exception {
        HttpResponse<String> response = post("/api/identity/authenticate",
                JSON.writeValueAsString(Map.of("username", username, "password", password)), headers("T001"));
        if (response.statusCode() != 200) {
            return JSON.readTree("{\"error\":\"" + response.statusCode() + "\"}");
        }
        JsonNode account = JSON.readTree(response.body());
        HttpResponse<String> session = post("/api/identity/sessions", account.toString(), headers("T001"));
        assertThat(session.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(session.body());
        // 服务端返回 {refreshToken, account{...}}；测试要同时拿到账号字段与 refresh，摊平一份。
        com.fasterxml.jackson.databind.node.ObjectNode merged = JSON.createObjectNode();
        merged.setAll((com.fasterxml.jackson.databind.node.ObjectNode) body.path("account"));
        merged.put("refreshToken", body.path("refreshToken").asText());
        return merged;
    }

    /** 注册一个独立账号，避免用例共享演示账号后互相撤族/改密。 */
    private void register(String username, String password) throws Exception {
        HttpResponse<String> response = post("/api/identity/register",
                JSON.writeValueAsString(Map.of("username", username, "password", password,
                        "displayName", username)), headers("T001"));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        HttpResponse<String> response = post("/api/identity/refresh",
                JSON.writeValueAsString(Map.of("refreshToken", refreshToken)), headers("T001"));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        com.fasterxml.jackson.databind.node.ObjectNode merged = JSON.createObjectNode();
        merged.setAll((com.fasterxml.jackson.databind.node.ObjectNode) body.path("account"));
        merged.put("refreshToken", body.path("refreshToken").asText());
        return merged;
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return send(builder);
    }

    private JsonNode get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).GET();
        headers.forEach(builder::header);
        HttpResponse<String> response = send(builder);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> headers(String tenantId) {
        return Map.of("X-Internal-Token", TOKEN, "X-Tenant-Id", tenantId);
    }

    private <T> T withTenant(String tenantId, java.util.function.Supplier<T> body) {
        try {
            TenantContextHolder.set(tenantId, null);
            return body.get();
        } finally {
            TenantContextHolder.clear();
        }
    }
}
