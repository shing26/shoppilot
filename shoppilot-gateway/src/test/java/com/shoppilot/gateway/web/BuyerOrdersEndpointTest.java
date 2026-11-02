package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** ADR 0075：买家订单只从已验签身份取归属，下游永远收到令牌里的 tenant/customer。 */
class BuyerOrdersEndpointTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = mock(HttpClient.class);
    private final GatewayProperties properties = mock(GatewayProperties.class);

    BuyerOrdersEndpointTest() {
        doReturn(new GatewayProperties.BizMock("http://127.0.0.1:8091", "biz-internal",
                Duration.ofSeconds(2), Duration.ofSeconds(2))).when(properties).bizmock();
    }

    @AfterEach
    void clearIdentity() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("买家令牌足够；下游拿到令牌里的 tenant/customer，而不是查询参数")
    void buyerIdentityDrivesDownstreamRequest() throws Exception {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-orders"));
        stub("[]");

        mvc().perform(MockMvcRequestBuilders.get("/api/v1/support/orders/mine?customerId=C155"))
                .andReturn();

        HttpRequest sent = captured();
        assertThat(sent.uri().toString()).isEqualTo("http://127.0.0.1:8091/api/orders/mine");
        assertThat(sent.headers().firstValue("X-Tenant-Id")).contains("T001");
        assertThat(sent.headers().firstValue("X-Customer-Id")).contains("C001");
        assertThat(sent.headers().firstValue("X-Internal-Token")).contains("biz-internal");
    }

    @Test
    @DisplayName("下游 200 时原样返回只读订单列表")
    void returnsOrderList() throws Exception {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-orders"));
        stub("[{\"orderNo\":\"90001\",\"status\":\"PAID\"}]");

        assertThat(mvc().perform(MockMvcRequestBuilders.get("/api/v1/support/orders/mine"))
                .andReturn().getResponse().getContentAsString())
                .contains("90001");
    }

    @Test
    @DisplayName("订单详情转发：路径与身份头都取自令牌上下文")
    void detailIsForwardedWithVerifiedIdentity() throws Exception {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-orders"));
        stub("{\"orderNo\":\"90001\"}");

        assertThat(mvc().perform(MockMvcRequestBuilders.get("/api/v1/support/orders/mine/90001"))
                .andReturn().getResponse().getContentAsString())
                .contains("90001");

        HttpRequest sent = captured();
        assertThat(sent.uri().toString()).isEqualTo("http://127.0.0.1:8091/api/orders/mine/90001");
        assertThat(sent.headers().firstValue("X-Tenant-Id")).contains("T001");
        assertThat(sent.headers().firstValue("X-Customer-Id")).contains("C001");
    }

    @Test
    @DisplayName("物流轨迹转发：下游路径带 /logistics 后缀")
    void logisticsIsForwardedUnderTheSameOrder() throws Exception {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-orders"));
        stub("{\"orderNo\":\"90001\",\"nodes\":[]}");

        mvc().perform(MockMvcRequestBuilders.get("/api/v1/support/orders/mine/90001/logistics")).andReturn();

        assertThat(captured().uri().toString())
                .isEqualTo("http://127.0.0.1:8091/api/orders/mine/90001/logistics");
    }

    @Test
    @DisplayName("下游 404 原样传成 404：越权与不存在同形，不改写成 502")
    void downstreamNotFoundStaysNotFound() throws Exception {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-orders"));
        stubStatus(404, "");

        var response = mvc().perform(MockMvcRequestBuilders.get("/api/v1/support/orders/mine/90001"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("订单号不合白名单：直接 404，且根本不发下游请求")
    void rejectsOrderNumbersOutsideTheWhitelist() throws Exception {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-orders"));

        var response = mvc().perform(MockMvcRequestBuilders.get("/api/v1/support/orders/mine/90001.1"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).isEmpty();
        verifyNoInteractions(http);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new BuyerOrdersController(http, properties,
                new ApiErrorWriter(JSON))).build();
    }

    private void stub(String body) throws Exception {
        stubStatus(200, body);
    }

    private void stubStatus(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(response).when(http).send(any(HttpRequest.class), any());
        doReturn(status).when(response).statusCode();
        doReturn(body).when(response).body();
    }

    private HttpRequest captured() {
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        try {
            verify(http).send(sent.capture(), any());
        } catch (Exception verified) {
            throw new IllegalStateException(verified);
        }
        return sent.getValue();
    }
}
