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

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new BuyerOrdersController(http, properties,
                new ApiErrorWriter(JSON))).build();
    }

    private void stub(String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(response).when(http).send(any(HttpRequest.class), any());
        doReturn(200).when(response).statusCode();
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
