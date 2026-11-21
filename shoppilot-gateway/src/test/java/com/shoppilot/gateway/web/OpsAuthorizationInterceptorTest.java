package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.tool.identity.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpsAuthorizationInterceptorTest {

    private GatewayProperties properties;
    private OpsAuthorizationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = mock(GatewayProperties.class);
        when(properties.ops()).thenReturn(new GatewayProperties.Ops(true, "ops-token"));
        interceptor = new OpsAuthorizationInterceptor(properties, new ApiErrorWriter(new ObjectMapper()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("买家读反馈复核队列 → 403")
    void buyerCannotReadFeedbackQueue() throws Exception {
        buyer();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/v1/support/ops/feedback/review-queue", null), response, new Object()))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("买家枚举店铺 → 403")
    void buyerCannotListTenants() throws Exception {
        buyer();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/v1/support/ops/tenants", null), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("买家读自己的工单 → 放行（白名单）")
    void buyerKeepsOwnTickets() throws Exception {
        buyer();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/v1/support/ops/tickets/mine", null), response, new Object())).isTrue();
    }

    @Test
    @DisplayName("买家读自己的工单详情 → 放行（白名单前缀）")
    void buyerKeepsOwnTicketDetail() throws Exception {
        buyer();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/v1/support/ops/tickets/mine/T123", null), response, new Object()))
                .isTrue();
    }

    @Test
    @DisplayName("正确 ops token → 放行")
    void opsTokenAllows() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/v1/support/ops/tenants", "ops-token"), response, new Object())).isTrue();
    }

    @Test
    @DisplayName("错误 ops token 且非 staff → 403")
    void wrongOpsTokenDenies() throws Exception {
        buyer();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/v1/support/ops/tenants", "wrong"), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("坐席账号 → 放行")
    void staffAllows() throws Exception {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "c", UserRole.AGENT, "A1", "agent"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/v1/support/ops/feedback/review-queue", null), response, new Object())).isTrue();
    }

    @Test
    @DisplayName("坐席读渠道连接 → 403，文案明确要求管理员")
    void agentCannotReadChannels() throws Exception {
        agent();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/api/v1/support/ops/channels", null), response, new Object()))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("role.denied").contains("管理员");
    }

    @Test
    @DisplayName("坐席枚举店铺 → 403，文案明确要求管理员")
    void agentCannotListTenants() throws Exception {
        agent();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/api/v1/support/ops/tenants", null), response, new Object()))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("role.denied").contains("管理员");
    }

    @Test
    @DisplayName("管理员可进渠道连接与店铺清单")
    void adminAllowsAdminSurfaces() throws Exception {
        admin();

        assertThat(interceptor.preHandle(request("/api/v1/support/ops/channels", null),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/api/v1/support/ops/tenants", null),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    @DisplayName("买家读渠道连接 → 403")
    void buyerCannotReadChannels() throws Exception {
        buyer();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/api/v1/support/ops/channels", null), response, new Object()))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("运维凭证仍可读管理员面：平台路径不是用户能力")
    void opsTokenStillAllowsAdminSurfaces() throws Exception {
        assertThat(interceptor.preHandle(request("/api/v1/support/ops/channels", "ops-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/api/v1/support/ops/tenants", "ops-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    @DisplayName("平台动作只认 ops token：AGENT/ADMIN 无 token 全部 403")
    void staffCannotUsePlatformActionsWithoutOpsToken() throws Exception {
        List<String> platformPaths = List.of(
                "/api/v1/support/ops/fault",
                "/api/v1/support/ops/stats",
                "/api/v1/support/ops/demo/reset",
                "/api/v1/support/ops/retrieval",
                "/api/v1/support/ops/epoch/bump",
                "/api/v1/support/ops/cache/flush",
                "/api/v1/support/ops/llm-fault",
                "/api/v1/support/ops/negative-cache");

        for (String path : platformPaths) {
            for (UserRole role : List.of(UserRole.AGENT, UserRole.ADMIN)) {
                TenantContext.set(new TenantContext.Identity(
                        "T001", "C001", "c", role, "U0001", role.name().toLowerCase()));
                MockHttpServletResponse response = new MockHttpServletResponse();
                assertThat(interceptor.preHandle(request(path, null), response, new Object()))
                        .as("%s 无 ops token 必须被拒绝", path)
                        .isFalse();
                assertThat(response.getStatus()).as(path).isEqualTo(403);
                String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
                assertThat(body).as(path).contains("role.denied").contains("运维");
            }
        }
    }

    @Test
    @DisplayName("平台动作带正确 ops token 仍放行")
    void opsTokenAllowsPlatformActions() throws Exception {
        List<String> platformPaths = List.of(
                "/api/v1/support/ops/fault",
                "/api/v1/support/ops/stats",
                "/api/v1/support/ops/demo/reset",
                "/api/v1/support/ops/retrieval",
                "/api/v1/support/ops/epoch/bump",
                "/api/v1/support/ops/cache/flush",
                "/api/v1/support/ops/llm-fault",
                "/api/v1/support/ops/negative-cache");

        for (String path : platformPaths) {
            assertThat(interceptor.preHandle(request(path, "ops-token"),
                    new MockHttpServletResponse(), new Object())).as(path).isTrue();
        }
    }

    @Test
    @DisplayName("分号矩阵参数不能绕过管理员专属面")
    void matrixParametersDoNotBypassAdminSurfaces() throws Exception {
        agent();
        for (String path : List.of(
                "/api/v1/support/ops/channels;x",
                "/api/v1/support/ops/channels;x/accounts",
                "/api/v1/support/ops/tenants;x")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(request(path, null), response, new Object()))
                    .as("%s 必须按 /channels 与 /tenants 的同一规则拒绝", path)
                    .isFalse();
            assertThat(response.getStatus()).as(path).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("分号矩阵参数不能绕过平台动作只认 ops token")
    void matrixParametersDoNotBypassPlatformActions() throws Exception {
        List<String> platformPaths = List.of(
                "/api/v1/support/ops/fault;x",
                "/api/v1/support/ops/stats;x",
                "/api/v1/support/ops/demo/reset;x",
                "/api/v1/support/ops/retrieval;x",
                "/api/v1/support/ops/epoch/bump;x",
                "/api/v1/support/ops/cache/flush;x",
                "/api/v1/support/ops/llm-fault;x",
                "/api/v1/support/ops/negative-cache;x");

        for (String path : platformPaths) {
            for (UserRole role : List.of(UserRole.AGENT, UserRole.ADMIN)) {
                TenantContext.set(new TenantContext.Identity(
                        "T001", "C001", "c", role, "U0001", role.name().toLowerCase()));
                MockHttpServletResponse response = new MockHttpServletResponse();
                assertThat(interceptor.preHandle(request(path, null), response, new Object()))
                        .as("%s 无 ops token 必须被拒绝", path)
                        .isFalse();
                assertThat(response.getStatus()).as(path).isEqualTo(403);
                String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
                assertThat(body).as(path).contains("role.denied").contains("运维");
            }
        }
    }

    @Test
    @DisplayName("运维面显式关闭 → 即使 token 正确也拒绝")
    void disabledOpsDeniesEvenWithToken() throws Exception {
        when(properties.ops()).thenReturn(new GatewayProperties.Ops(false, "ops-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/v1/support/ops/tenants", "ops-token"), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    private void buyer() {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "c", UserRole.BUYER, null, null));
    }

    private void agent() {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "c", UserRole.AGENT, "A1", "agent"));
    }

    private void admin() {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "c", UserRole.ADMIN, "A0", "admin"));
    }

    private MockHttpServletRequest request(String path, String opsToken) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        if (opsToken != null) {
            request.addHeader("X-Ops-Token", opsToken);
        }
        return request;
    }
}
