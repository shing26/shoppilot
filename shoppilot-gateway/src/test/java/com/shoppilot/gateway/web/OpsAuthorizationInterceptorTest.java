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

    private MockHttpServletRequest request(String path, String opsToken) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        if (opsToken != null) {
            request.addHeader("X-Ops-Token", opsToken);
        }
        return request;
    }
}
