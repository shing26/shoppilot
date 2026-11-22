package com.shoppilot.gateway.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.web.ApiErrorWriter;
import com.shoppilot.tool.identity.AccountView;
import com.shoppilot.tool.identity.RefreshSession;
import com.shoppilot.tool.identity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 网关侧身份生命周期契约（round56 票 224 / ADR 0086）。
 *
 * <p>这里量的是「网关怎么把身份域的结局翻译成浏览器能用的形状」：refresh 只走 HttpOnly Cookie、
 * 复用/过期清 Cookie、改密要求已验签账号、{@code /auth/me} 回读服务端最新角色。
 * 真正的轮换与锁定在 biz-mock 的 {@code IdentityLifecycleTest} 里验。
 */
class IdentityLifecycleHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "{{SECRET_9wqk3zta}}-at-least-32-bytes";

    private final JwtService jwtService = new JwtService(SECRET);
    private final IdentityClient identity = mock(IdentityClient.class);

    private MockMvc mvc() {
        ApiErrorWriter errors = new ApiErrorWriter(JSON);
        return MockMvcBuilders.standaloneSetup(new AccountController(identity, jwtService, errors)).build();
    }

    @Test
    @DisplayName("登录：响应体只有 access token，refresh 只出现在 HttpOnly Cookie 里")
    void loginPutsRefreshInHttpOnlyCookieOnly() throws Exception {
        AccountView account = new AccountView("U1001", "T001", "agent1", UserRole.AGENT, null, "坐席", 3);
        when(identity.authenticate(eq("T001"), eq("agent1"), eq("{{SECRET_c4b1xv8s}}")))
                .thenReturn(new IdentityClient.Attempt(200, account, null));
        when(identity.openSession(eq("T001"), any(AccountView.class)))
                .thenReturn(new IdentityClient.RefreshAttempt(200,
                        new RefreshSession("v1.VDAwMQ.opaque-secret", account), null, null));

        MvcResult result = mvc().perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"T001\",\"username\":\"agent1\",\"password\":\"{{SECRET_c4b1xv8s}}\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cookie).contains("sp_rt=v1.VDAwMQ.opaque-secret");
        assertThat(cookie).contains("HttpOnly");
        assertThat(cookie).contains("SameSite=Strict");
        assertThat(cookie).contains("Path=/auth");

        String body = utf8(result);
        assertThat(body).doesNotContain("opaque-secret");
        JsonNode json = JSON.readTree(body);
        assertThat(json.path("token").asText()).isNotBlank();
        // 口令版本进 access token：改密后旧令牌的 pv 落后
        assertThat(jwtService.verify(json.path("token").asText(), "c").orElseThrow().role())
                .isEqualTo(UserRole.AGENT);
    }

    @Test
    @DisplayName("刷新复用：401 + refresh_reuse，并清掉 Cookie")
    void reuseClearsCookie() throws Exception {
        when(identity.refresh(eq("T001"), eq("v1.VDAwMQ.old")))
                .thenReturn(new IdentityClient.RefreshAttempt(401, null,
                        "登录凭证已失效，请重新登录", "refresh_reuse"));

        MvcResult result = mvc().perform(post("/auth/refresh").cookie(
                        new jakarta.servlet.http.Cookie("sp_rt", "v1.VDAwMQ.old")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
        assertThat(JSON.readTree(utf8(result)).path("code").asText()).isEqualTo("refresh_reuse");
    }

    @Test
    @DisplayName("改密：无令牌 401；令牌账号 id 取自验签结果而不是请求体")
    void changePasswordUsesTokenAccount() throws Exception {
        assertThat(mvc().perform(post("/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"bbbbbbbb\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        String token = jwtService.issueAccess("T001", "C001", UserRole.AGENT, "U1001", "agent1", 0);
        when(identity.changePassword(eq("T001"), eq("U1001"), eq("{{SECRET_7s0pklqa}}"), eq("{{SECRET_z8n5rywv}}")))
                .thenReturn(new IdentityClient.Attempt(200,
                        new AccountView("U1001", "T001", "agent1", UserRole.AGENT, null, "坐席", 1), null));

        MvcResult result = mvc().perform(post("/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"{{SECRET_7s0pklqa}}\",\"newPassword\":\"{{SECRET_z8n5rywv}}\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(utf8(result)).path("changed").asBoolean()).isTrue();
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }

    @Test
    @DisplayName("/auth/me 回读服务端角色：令牌里是 AGENT，服务端已改成 ADMIN 就按 ADMIN 返回")
    void meReflectsServerSideRole() throws Exception {
        String token = jwtService.issueAccess("T001", "C001", UserRole.AGENT, "U1001", "agent1", 0);
        when(identity.account("T001", "U1001")).thenReturn(new IdentityClient.Attempt(200,
                new AccountView("U1001", "T001", "agent1", UserRole.ADMIN, null, "坐席", 0), null));

        MvcResult result = mvc().perform(get("/auth/me").header("Authorization", "Bearer " + token)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(utf8(result));
        assertThat(body.path("role").asText()).isEqualTo("ADMIN");
        assertThat(body.path("capabilities").toString()).contains("channels");
    }

    @Test
    @DisplayName("/auth/me 对已停用账号返回 401，不让旧 access 继续当通行证")
    void meRejectsDisabledAccount() throws Exception {
        String token = jwtService.issueAccess("T001", "C001", UserRole.AGENT, "U1002", "gone", 0);
        // 账号仍然存在、角色也还是 AGENT，只是 active=false：这正是 Verifier 抓到的原场景。
        // 若只桩 404，测的就不是「停用态」而是「账号被删」。
        when(identity.account("T001", "U1002"))
                .thenReturn(new IdentityClient.Attempt(200,
                        new AccountView("U1002", "T001", "gone", UserRole.AGENT, null, "已停用", 0, false),
                        null));

        MvcResult result = mvc().perform(get("/auth/me").header("Authorization", "Bearer " + token)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("旧 mock 令牌没有账号 id：/auth/me 不查身份域，仍按令牌角色解释")
    void legacyMockTokenStillWorks() throws Exception {
        String token = jwtService.issue("T001", "C155");
        MvcResult result = mvc().perform(get("/auth/me").header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(utf8(result)).path("role").asText()).isEqualTo("BUYER");
    }

    @Test
    @DisplayName("登出：无 Cookie 也 204，且总是清 Cookie（幂等）")
    void logoutIsIdempotent() throws Exception {
        MvcResult result = mvc().perform(post("/auth/logout")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }

    @Test
    @DisplayName("刷新缺 Cookie：401 且不触达身份域")
    void refreshWithoutCookieIsUnauthorized() throws Exception {
        MvcResult result = mvc().perform(post("/auth/refresh")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }

    @Test
    @DisplayName("账号管理端点：ADMIN 可用，AGENT 403，匿名 401")
    void accountAdminEndpointsRequireAdminRole() throws Exception {
        String admin = jwtService.issueAccess("T001", null, UserRole.ADMIN, "U-admin", "admin", 0);
        String agent = jwtService.issueAccess("T001", null, UserRole.AGENT, "U-agent", "agent", 0);
        when(identity.setAccountRole(eq("T001"), eq("U1001"), eq(UserRole.AGENT)))
                .thenReturn(new IdentityClient.Attempt(200,
                        new AccountView("U1001", "T001", "someone", UserRole.AGENT, null, "某人", 0), null));

        MvcResult anonymous = mvc().perform(post("/auth/accounts/U1001/role")
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AGENT\"}")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);

        MvcResult forbidden = mvc().perform(post("/auth/accounts/U1001/role")
                        .header("Authorization", "Bearer " + agent)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AGENT\"}"))
                .andReturn();
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(JSON.readTree(utf8(forbidden)).path("code").asText()).isEqualTo("role.denied");

        MvcResult allowed = mvc().perform(post("/auth/accounts/U1001/role")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AGENT\"}"))
                .andReturn();
        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(utf8(allowed)).path("role").asText()).isEqualTo("AGENT");
    }

    @Test
    @DisplayName("锁定 429：透传 Retry-After，错误码不退化成 invalid_request")
    void lockoutKeepsRetryAfterAndCode() throws Exception {
        when(identity.authenticate(eq("T001"), eq("locked"), anyString()))
                .thenReturn(new IdentityClient.Attempt(429, null, "尝试次数过多，请稍后再试", null, 600L));

        MvcResult result = mvc().perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"T001\",\"username\":\"locked\",\"password\":\"x\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("600");
        JsonNode body = JSON.readTree(utf8(result));
        assertThat(body.path("code").asText()).isEqualTo("login_locked");
        assertThat(body.path("message").asText()).isEqualTo("尝试次数过多，请稍后再试");
    }

    private static String utf8(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
