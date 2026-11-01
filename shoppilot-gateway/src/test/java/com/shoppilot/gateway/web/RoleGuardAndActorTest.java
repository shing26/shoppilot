package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.BizMockClient;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.config.DevDefaultsPolicy;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.config.TenantDomainRegistry;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.identity.TenantContext.Identity;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.llm.LlmFaultInjector;
import com.shoppilot.gateway.llm.TokenBudget;
import com.shoppilot.tool.identity.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 角色守卫与自报身份退役（round25 票 82 / ADR 0058 第 3、4 条）。
 *
 * <p>量的是三件事：① 谁能过这道门；② 下游看到的操作人是谁；③ 那个操作人可不可信。
 * 第三件才是本轮真正的新东西——前两件在票 82 之前就有（一条 ops token），只是那时操作人永远不可信。
 */
class RoleGuardAndActorTest {

    private static final String OPS_TOKEN = "unit-ops-token";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GatewayProperties properties = mock(GatewayProperties.class);
    private final HttpClient http = mock(HttpClient.class);

    RoleGuardAndActorTest() {
        when(properties.ops()).thenReturn(new GatewayProperties.Ops(true, OPS_TOKEN));
        when(properties.bizmock()).thenReturn(new GatewayProperties.BizMock(
                "http://127.0.0.1:8091", "internal", Duration.ofSeconds(2), Duration.ofSeconds(2)));
        when(properties.ticket()).thenReturn(new GatewayProperties.Ticket(
                "http://127.0.0.1:8092", "ticket-internal", Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }

    @AfterEach
    void clearIdentity() {
        TenantContext.clear();
    }

    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private MockMvc mvc() {
        ApiErrorWriter writer = new ApiErrorWriter(JSON);
        return MockMvcBuilders.standaloneSetup(new OpsController(http, properties, mock(BizMockClient.class), JSON,
                mock(LlmFaultInjector.class), mock(CacheService.class), mock(KbEpoch.class),
                mock(TokenBudget.class), mock(HybridRetriever.class), mock(TenantDomainRegistry.class),
                mock(DevDefaultsPolicy.class), writer,
                registry))
                .build();
    }

    @Test
    @DisplayName("买家身份、无运维凭证 → 403，而且请求没出网关")
    void buyerWithoutOpsTokenIsDenied() throws Exception {
        TenantContext.set(new Identity("T001", "C001", "conv-1", UserRole.BUYER, null));
        stubTicketService(204, "");

        MockHttpServletResponse response = claim(null, null);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(envelope(response).path("code").asText()).isEqualTo("role.denied");
        verify(http, never()).send(any(), any());
    }

    @Test
    @DisplayName("坐席账号不必持运维凭证：下游拿到的操作人是令牌里的账号 id，并标成已认证")
    void staffRoleWorksWithoutOpsToken() throws Exception {
        TenantContext.set(new Identity("T001", "C001", "conv-2", UserRole.AGENT, "U0007"));
        stubTicketService(204, "");
        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);

        claim(null, null);

        verify(http).send(captured.capture(), any());
        assertThat(captured.getValue().headers().firstValue("X-Actor")).contains("U0007");
        assertThat(captured.getValue().headers().firstValue("X-Actor-Authenticated")).contains("true");
    }

    @Test
    @DisplayName("伪造的 X-Agent 盖不过令牌：自称别人也不行")
    void forgedSelfReportedAgentIsIgnored() throws Exception {
        TenantContext.set(new Identity("T001", "C001", "conv-3", UserRole.AGENT, "U0007"));
        stubTicketService(204, "");
        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);

        claim(null, "impersonated-colleague");

        verify(http).send(captured.capture(), any());
        assertThat(captured.getValue().headers().firstValue("X-Actor"))
                .as("操作人只从令牌解，自称一律不算数").contains("U0007");
    }

    @Test
    @DisplayName("只有运维凭证的老路径照常工作，但操作人被标成未认证（调试台与那批验收脚本走的就是这条）")
    void opsTokenPathStillWorksButIsMarkedUnauthenticated() throws Exception {
        // mock 令牌：没有账号 id，角色按 BUYER 解释（票 81）
        TenantContext.set(new Identity("T001", "C001", "conv-4"));
        stubTicketService(204, "");
        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);

        claim(OPS_TOKEN, "ops-on-duty");

        verify(http).send(captured.capture(), any());
        assertThat(captured.getValue().headers().firstValue("X-Actor")).contains("ops-on-duty");
        assertThat(captured.getValue().headers().firstValue("X-Actor-Authenticated"))
                .as("没有账号 id 就不是认证过的操作人——照登，不假装有据").contains("false");
    }

    @Test
    @DisplayName("运维面被显式关掉时报的是 ops.disabled，不是 role.denied")
    void disabledOpsKeepsItsOwnMessage() throws Exception {
        when(properties.ops()).thenReturn(new GatewayProperties.Ops(false, OPS_TOKEN));
        TenantContext.set(new Identity("T001", "C001", "conv-5", UserRole.BUYER, null));
        stubTicketService(204, "");

        MockHttpServletResponse response = claim(null, null);

        assertThat(envelope(response).path("code").asText()).isEqualTo("ops.disabled");
    }

    @Test
    @DisplayName("署名用登录账号名而不是账号 id——工单 assignee 与审计 actor 都是人读的")
    void actorPrefersTheUsernameOverTheAccountId() throws Exception {
        TenantContext.set(new Identity("T001", "C001", "conv-6", UserRole.AGENT, "U0a1b2c3d4e5", "agent"));
        stubTicketService(204, "");
        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);

        claim(null, null);

        verify(http).send(captured.capture(), any());
        assertThat(captured.getValue().headers().firstValue("X-Actor")).contains("agent");
    }

    /**
     * round43 票 186（ADR 0078）：知识修订三件套（提交 / 发布 / 驳回）走 guardedBizMock——
     * 两条准入线取或：坐席账号不用持运维凭证；只有运维凭证的老路径也放行（非资金动作，ADR 0063）。
     * 身份头由网关补，浏览器不带；成功路径各自埋一个修订计数。
     */
    @Test
    @DisplayName("坐席提交修订：路径与身份头由网关补齐，请求体逐字节透传")
    void staffFilesRevisionThroughProxy() throws Exception {
        TenantContext.set(new Identity("T001", "C001", "conv-rev-1", UserRole.AGENT, "U0007"));
        String body = "{\"title\":\"退换货口径\",\"body\":\"## 生效范围\",\"intent\":\"POLICY_RETURN\","
                + "\"domain\":\"ECOMMERCE\",\"scope\":\"SHOP\"}";
        stubTicketService(200, "{\"id\":\"KR1\",\"status\":\"DRAFT\"}");
        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);

        MockHttpServletResponse response = mvc().perform(post("/api/v1/support/ops/feedback/FB1/revision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        verify(http).send(captured.capture(), any());
        HttpRequest sent = captured.getValue();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri().toString()).isEqualTo("http://127.0.0.1:8091/api/feedback/FB1/revision");
        assertThat(sent.headers().firstValue("X-Internal-Token")).contains("internal");
        assertThat(sent.headers().firstValue("X-Tenant-Id")).contains("T001");
        assertThat(sent.headers().firstValue("X-Customer-Id")).contains("C001");
        assertThat(sent.headers().firstValue("X-Actor")).contains("U0007");
        assertThat(sent.bodyPublisher().orElseThrow().contentLength())
                .as("请求体逐字节透传，网关不重排字段").isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(registry.get("shoppilot_knowledge_revisions_total")
                .tag("result", "filed").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("买家无运维凭证不能提交修订：403，且请求没出网关")
    void buyerCannotFileRevision() throws Exception {
        TenantContext.set(new Identity("T001", "C001", "conv-rev-2", UserRole.BUYER, null));

        MockHttpServletResponse response = mvc().perform(post("/api/v1/support/ops/feedback/FB1/revision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"body\":\"y\",\"intent\":\"POLICY_RETURN\","
                                + "\"domain\":\"ECOMMERCE\",\"scope\":\"SHOP\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(envelope(response).path("code").asText()).isEqualTo("role.denied");
        verify(http, never()).send(any(), any());
    }

    @Test
    @DisplayName("发布修订是人工门：坐席即可；成功一次计一次 published")
    void staffPublishesRevisionAndCounterCounts() throws Exception {
        TenantContext.set(new Identity("T001", "C001", "conv-rev-3", UserRole.AGENT, "U0007"));
        stubTicketService(200, "{\"id\":\"KR1\",\"status\":\"PUBLISHED\"}");

        MockHttpServletResponse response = mvc().perform(post("/api/v1/support/ops/knowledge/revisions/KR1/publish"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captured.capture(), any());
        assertThat(captured.getValue().method()).isEqualTo("POST");
        assertThat(captured.getValue().uri().toString())
                .isEqualTo("http://127.0.0.1:8091/api/knowledge/revisions/KR1/publish");
        assertThat(registry.get("shoppilot_knowledge_revisions_total")
                .tag("result", "published").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("复核完成写口：坐席可标 REVIEWED，PATCH 方法、路径与租户头由网关补齐（ADR 0078 Decision 5）")
    void staffMarksFeedbackReviewedThroughProxy() throws Exception {
        TenantContext.set(new Identity("T001", "C001", "conv-rev-4", UserRole.AGENT, "U0007"));
        stubTicketService(200, "{\"reviewStatus\":\"REVIEWED\"}");
        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);

        MockHttpServletResponse response = mvc().perform(patch("/api/v1/support/ops/feedback/FB1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REVIEWED\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        verify(http).send(captured.capture(), any());
        HttpRequest sent = captured.getValue();
        assertThat(sent.method()).isEqualTo("PATCH");
        assertThat(sent.uri().toString()).isEqualTo("http://127.0.0.1:8091/api/feedback/FB1/review");
        assertThat(sent.headers().firstValue("X-Internal-Token")).contains("internal");
        assertThat(sent.headers().firstValue("X-Tenant-Id")).contains("T001");
        assertThat(sent.headers().firstValue("X-Actor")).contains("U0007");
    }

    @Test
    @DisplayName("买家不能写复核状态：403，且请求没出网关")
    void buyerCannotMarkFeedbackReviewed() throws Exception {
        TenantContext.set(new Identity("T001", "C001", "conv-rev-5", UserRole.BUYER, null));

        MockHttpServletResponse response = mvc().perform(patch("/api/v1/support/ops/feedback/FB1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REVIEWED\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(envelope(response).path("code").asText()).isEqualTo("role.denied");
        verify(http, never()).send(any(), any());
    }

    private MockHttpServletResponse claim(String opsToken, String selfReported) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/support/ops/tickets/T-1/claim")
                .contentType(MediaType.APPLICATION_JSON);
        if (opsToken != null) {
            request.header("X-Ops-Token", opsToken);
        }
        if (selfReported != null) {
            request.header("X-Agent", selfReported);
        }
        return mvc().perform(request).andReturn().getResponse();
    }

    /** MockMvc 的响应字节按容器默认字符集解，中文会花；写出去的是 UTF-8，判据按 UTF-8 读回来。 */
    private static JsonNode envelope(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private void stubTicketService(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        // send() 的返回类型参数由 BodyHandler 推出来，when(...).thenReturn(...) 会撞上
        // HttpResponse<String> 与 HttpResponse<Object> 不能转换；doReturn 才不管泛型。
        doReturn(response).when(http).send(any(HttpRequest.class), any());
    }
}
