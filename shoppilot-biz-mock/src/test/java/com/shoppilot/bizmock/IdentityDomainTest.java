package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.bizmock.domain.UserAccount;
import com.shoppilot.bizmock.repo.UserAccountRepository;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份域（round25 票 80 / ADR 0056、0058）。
 *
 * <p>走真实 HTTP 而不直接调 service：租户上下文是由 {@code InternalAuthFilter} 从请求头写进去的，
 * 跳过 HTTP 等于把「租户从哪来」当成前提用掉——而这正是本票要验的东西。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 独立 H2：本用例会真的建号停号，共用默认库会让别的用例撞上同一批用户名
        "spring.datasource.url=jdbc:h2:mem:identity-domain;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.seed.orders=20",
        "shoppilot.bizmock.seed.customers=5",
        "shoppilot.bizmock.internal-token=test-internal"
})
class IdentityDomainTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;
    @Autowired
    UserAccountRepository accounts;
    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("注册买家账号：回得到主体与角色，且不是运维凭证换来的")
    void registerCreatesBuyerAccount() throws Exception {
        ResponseEntity<String> response = post("/api/identity/register",
                "{\"username\":\"alice\",\"password\":\"alice-pass-1\",\"displayName\":\"爱丽丝\"}",
                headers("T001"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode account = JSON.readTree(response.getBody());
        assertThat(account.path("role").asText()).isEqualTo("BUYER");
        assertThat(account.path("tenantId").asText()).isEqualTo("T001");
        // 买家账号必须挂到一个业务主体上，否则登录之后「我的订单」没有主体可查
        assertThat(account.path("subjectRef").asText()).isNotBlank();
    }

    @Test
    @DisplayName("口令只以 BCrypt 落库，明文一个字节都不留")
    void passwordIsStoredAsBcryptHash() throws Exception {
        post("/api/identity/register",
                "{\"username\":\"bob\",\"password\":\"bob-pass-1\"}", headers("T001")).getBody();

        String hash = withTenant("T001", () -> accounts.findByUsername("bob").orElseThrow().getPasswordHash());
        assertThat(hash).startsWith("$2");
        assertThat(hash).doesNotContain("bob-pass-1");
        // 列里也不该有第二份明文：整行扫一遍更省心，避免「哈希列安全、别处漏了」
        assertThat(jdbc.queryForObject(
                "select count(*) from users where password_hash like '%bob-pass-1%'", Long.class)).isZero();
    }

    @Test
    @DisplayName("同租户重名拒（409），跨店同名允许——用户名不是全局命名空间")
    void usernameIsUniquePerTenantNotGlobally() throws Exception {
        assertThat(post("/api/identity/register",
                "{\"username\":\"same\",\"password\":\"same-pass-1\"}", headers("T001")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/api/identity/register",
                "{\"username\":\"same\",\"password\":\"same-pass-1\"}", headers("T001")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("/api/identity/register",
                "{\"username\":\"same\",\"password\":\"same-pass-1\"}", headers("T002")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("口令短于 8 位拒（400）")
    void weakPasswordIsRejected() throws Exception {
        assertThat(post("/api/identity/register",
                "{\"username\":\"carol\",\"password\":\"short\"}", headers("T001")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("口令错与用户不存在对外是同一句话（401 + 逐字相同的文案）")
    void wrongPasswordAndUnknownUserLookIdentical() throws Exception {
        post("/api/identity/register",
                "{\"username\":\"dave\",\"password\":\"dave-pass-1\"}", headers("T001"));

        ResponseEntity<String> wrongPassword = post("/api/identity/authenticate",
                "{\"username\":\"dave\",\"password\":\"dave-pass-2\"}", headers("T001"));
        ResponseEntity<String> unknownUser = post("/api/identity/authenticate",
                "{\"username\":\"nobody\",\"password\":\"dave-pass-1\"}", headers("T001"));

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // 逐字比较：文案分叉就是账号枚举被打开的那一条缝
        assertThat(unknownUser.getBody()).isEqualTo(wrongPassword.getBody());
    }

    @Test
    @DisplayName("停用的账号登不进来，对外文案与「口令错」逐字相同")
    void disabledAccountCannotLogIn() throws Exception {
        post("/api/identity/register",
                "{\"username\":\"erin\",\"password\":\"erin-pass-1\"}", headers("T001"));
        post("/api/identity/register",
                "{\"username\":\"dave\",\"password\":\"dave-pass-1\"}", headers("T001"));

        // 先证明 erin 本来是登得进去的：不钉这一条，「登不进」可以是「这条路根本不通」，
        // 那时后面的断言全都白写（第一版就踩了这个：拿同一个已停用账号当「口令错」的对照锚点，
        // 两边都返回 401，断言恒成立，把密码校验被绕过这件事一起放过去了）。
        assertThat(post("/api/identity/authenticate",
                "{\"username\":\"erin\",\"password\":\"erin-pass-1\"}", headers("T001")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        withTenant("T001", () -> {
            UserAccount account = accounts.findByUsername("erin").orElseThrow();
            account.disable();
            return accounts.save(account);
        });

        // 停用之后口令**正确**也进不来
        ResponseEntity<String> disabled = post("/api/identity/authenticate",
                "{\"username\":\"erin\",\"password\":\"erin-pass-1\"}", headers("T001"));
        // 对照锚点放在一个**仍然启用**的账号上，口令是错的
        ResponseEntity<String> wrongPassword = post("/api/identity/authenticate",
                "{\"username\":\"dave\",\"password\":\"dave-pass-2\"}", headers("T001"));

        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(disabled.getBody()).isEqualTo(wrongPassword.getBody());
    }

    @Test
    @DisplayName("跨租户拿别人的用户名登不进来（T001 的号在 T002 查不到）")
    void authenticationIsTenantScoped() throws Exception {
        post("/api/identity/register",
                "{\"username\":\"frank\",\"password\":\"frank-pass-1\"}", headers("T001"));

        ResponseEntity<String> crossTenant = post("/api/identity/authenticate",
                "{\"username\":\"frank\",\"password\":\"frank-pass-1\"}", headers("T002"));

        assertThat(crossTenant.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("缺内部凭证或缺租户上下文一律 401——身份面不进内部凭证")
    void identityEndpointsRequireInternalTokenAndTenant() throws Exception {
        String body = "{\"username\":\"x\",\"password\":\"xxxx-1\"}";

        HttpHeaders noToken = headers("T001");
        noToken.remove("X-Internal-Token");
        assertThat(post("/api/identity/authenticate", body, noToken).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders noTenant = headers(null);
        assertThat(post("/api/identity/authenticate", body, noTenant).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("仓库里没有默认口令：不显式给口令时一条演示账号都不建")
    void noDemoAccountsWithoutExplicitPassword() {
        // 本类刻意没配 shoppilot.bizmock.identity.demo-password，正是要钉这一格
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
    }

    @Test
    @DisplayName("schema 断言要跟着加表：V7 建出了 users")
    void usersTableExists() {
        assertThat(jdbc.queryForList(
                "select lower(table_name) from information_schema.tables where table_schema = 'PUBLIC'",
                String.class)).contains("users");
    }

    /**
     * 一律用 JDK 的 {@code HttpClient}，不用 {@code TestRestTemplate}。
     *
     * <p>本类要断言的多数结局是 401，而 {@code HttpURLConnection} 拿到 401 会尝试重新认证；
     * 请求体是流式发送的，重发不了，于是每次都变成
     * {@code "cannot retry due to server authentication, in streaming mode"}——
     * 报的是一个传输层的错，把「401 是不是真的返回了」这件事盖掉了。
     */
    private ResponseEntity<String> post(String path, String body, HttpHeaders headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode()).body(response.body());
        } catch (IOException | InterruptedException transportFailure) {
            throw new IllegalStateException("身份端点请求失败：" + transportFailure, transportFailure);
        }
    }

    private HttpHeaders headers(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", TOKEN);
        if (tenantId != null) {
            headers.set("X-Tenant-Id", tenantId);
        }
        return headers;
    }

    /** 仓储要走租户过滤器，所以每次按租户切换上下文再清理（虚拟线程下 ThreadLocal 不会自动回收）。 */
    private <T> T withTenant(String tenantId, java.util.function.Supplier<T> body) {
        try {
            TenantContextHolder.set(tenantId, null);
            return body.get();
        } finally {
            TenantContextHolder.clear();
        }
    }
}