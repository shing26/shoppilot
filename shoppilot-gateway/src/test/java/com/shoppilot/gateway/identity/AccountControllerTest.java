package com.shoppilot.gateway.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.web.ApiErrorWriter;
import com.shoppilot.tool.identity.AccountView;
import com.shoppilot.tool.identity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * 登录面（round25 票 81 / ADR 0058）。
 *
 * <p>用 standaloneSetup + mock 身份域，与 {@code RestErrorEnvelopeTest} 同一口径：本仓不起容器。
 * 真正的 BCrypt 在 biz-mock 侧验（票 80 的 {@code IdentityDomainTest}），这里量的是
 * 「网关把账号换成了什么令牌」——那是本票的全部。
 */
class AccountControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "test-secret-for-account-controller-0123456789";

    private final JwtService jwtService = new JwtService(SECRET);
    private final IdentityClient identity = mock(IdentityClient.class);

    private MockMvc mvc() {
        ApiErrorWriter errors = new ApiErrorWriter(JSON);
        return MockMvcBuilders.standaloneSetup(new AccountController(identity, jwtService, errors)).build();
    }

    @Test
    @DisplayName("登录成功：令牌里带角色与账号 id，回读得到")
    void loginIssuesRoleBearingToken() throws Exception {
        when(identity.authenticate(eq("T001"), eq("agent1"), eq("agent-pass-1")))
                .thenReturn(new IdentityClient.Attempt(200,
                        new AccountView("U0001", "T001", "agent1", UserRole.AGENT, null, "坐席小林"), null));

        JsonNode body = login("{\"tenantId\":\"T001\",\"username\":\"agent1\",\"password\":\"agent-pass-1\"}");

        assertThat(body.path("role").asText()).isEqualTo("AGENT");
        assertThat(body.path("accountId").asText()).isEqualTo("U0001");
        assertThat(jwtService.verify(body.path("token").asText(), "c").orElseThrow().role())
                .isEqualTo(UserRole.AGENT);
    }

    @Test
    @DisplayName("租户取自库里那一行，不取自请求里的 tenantId")
    void tokenTenantComesFromTheStoredAccount() throws Exception {
        when(identity.authenticate(eq("T001"), eq("bob"), anyString()))
                .thenReturn(new IdentityClient.Attempt(200,
                        new AccountView("U0002", "T002", "bob", UserRole.BUYER, "C001", "鲍勃"), null));

        JsonNode body = login("{\"tenantId\":\"T001\",\"username\":\"bob\",\"password\":\"whatever-1\"}");

        // 请求说的是「去 T001 这张表里找 bob」，身份域回的是「bob 这行属于 T002」——以库里的为准
        assertThat(body.path("tenantId").asText()).isEqualTo("T002");
        assertThat(jwtService.verify(body.path("token").asText(), "c").orElseThrow().tenantId()).isEqualTo("T002");
    }

    @Test
    @DisplayName("口令错与用户不存在回同一句话（401），文案由身份域给")
    void rejectionMessageIsPassedThroughVerbatim() throws Exception {
        when(identity.authenticate(anyString(), anyString(), anyString()))
                .thenReturn(new IdentityClient.Attempt(401, null, "用户名或口令不对"));

        MvcResult result = mvc().perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"T001\",\"username\":\"x\",\"password\":\"y\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        JsonNode body = JSON.readTree(utf8(result));
        assertThat(body.path("message").asText()).isEqualTo("用户名或口令不对");
        assertThat(body.path("code").asText()).isEqualTo("unauthorized");
    }

    @Test
    @DisplayName("身份域不可达是 502，与「被拒」不是同一件事")
    void unreachableIdentityDomainIsBadGateway() throws Exception {
        when(identity.authenticate(anyString(), anyString(), anyString()))
                .thenReturn(new IdentityClient.Attempt(0, null, "身份服务不可达"));

        MvcResult result = mvc().perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"T001\",\"username\":\"x\",\"password\":\"y\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("downstream_unreachable");
    }

    @Test
    @DisplayName("/auth/me 要令牌：无令牌 401（过滤器放行不等于端点不设防）")
    void meRequiresAToken() throws Exception {
        MvcResult result = mvc().perform(get("/auth/me")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("/auth/me 回读出角色与账号")
    void meEchoesTheTokenSubject() throws Exception {
        String token = jwtService.issue("T001", "C001", UserRole.AGENT, "U0001");

        MvcResult result = mvc().perform(get("/auth/me").header("Authorization", "Bearer " + token)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("role").asText()).isEqualTo("AGENT");
        assertThat(body.path("accountId").asText()).isEqualTo("U0001");
        assertThat(body.path("tenantId").asText()).isEqualTo("T001");
    }

    @Test
    @DisplayName("旧令牌没有 role claim，按 BUYER 解释——调试台与那批验收脚本一行都不用改")
    void legacyTokenWithoutRoleIsTreatedAsBuyer() throws Exception {
        String legacy = jwtService.issue("T001", "C155");

        MvcResult result = mvc().perform(get("/auth/me").header("Authorization", "Bearer " + legacy)).andReturn();

        assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("role").asText())
                .isEqualTo("BUYER");
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("accountId").isNull()).isTrue();
    }

    private JsonNode login(String body) throws Exception {
        MvcResult result = mvc().perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(utf8(result));
    }

    /**
     * MockMvc 的响应字节按容器默认字符集解，中文会花成一串「ç¨æ·…」。
     * 写出去的是 UTF-8，判据就得按 UTF-8 读回来（{@code RestErrorEnvelopeTest} 踩过一次，记在这里）。
     */
    private static String utf8(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}