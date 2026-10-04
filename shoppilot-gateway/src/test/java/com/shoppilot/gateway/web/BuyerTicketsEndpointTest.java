package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.BizMockClient;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.config.DevDefaultsPolicy;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.llm.LlmFaultInjector;
import com.shoppilot.gateway.llm.TokenBudget;
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

/**
 * 买家侧的工单入口（round27 票 92，网关这一半）。
 *
 * <p>断两件事：**买家不该被运维凭证拦住**，以及**调用方传进来的买家号不是数**——
 * 下游看到的必须永远是已验签身份里那个。
 */
class BuyerTicketsEndpointTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = mock(HttpClient.class);
    private final GatewayProperties properties = mock(GatewayProperties.class);

    BuyerTicketsEndpointTest() {
        doReturn(new GatewayProperties.Ticket("http://127.0.0.1:8092", "ticket-internal",
                Duration.ofSeconds(2), Duration.ofSeconds(2))).when(properties).ticket();
    }

    @AfterEach
    void clearIdentity() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("带买家令牌就能读，不要求运维凭证")
    void buyerTokenIsEnough() throws Exception {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-buyer"));
        stubTicketService("[]");

        assertThat(mvc().perform(MockMvcRequestBuilders.get("/api/v1/support/ops/tickets/mine"))
                .andReturn().getResponse().getStatus())
                .as("买家不该被运维凭证拦住").isEqualTo(200);
    }

    @Test
    @DisplayName("下游拿到的买家号恒来自令牌：调用方传什么参数都不改变它")
    void buyerIdAlwaysComesFromTheToken() throws Exception {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-buyer"));
        stubTicketService("[]");

        mvc().perform(MockMvcRequestBuilders.get("/api/v1/support/ops/tickets/mine?customerId=C155"))
                .andReturn();

        assertThat(captured().uri().toString())
                .as("转发出去的地址里不该带买家号——下游从 X-Customer-Id 头读")
                .isEqualTo("http://127.0.0.1:8092/api/tickets/mine");
        assertThat(captured().headers().firstValue("X-Customer-Id")).contains("C001");
    }

    private MockMvc mvc() {
        ApiErrorWriter writer = new ApiErrorWriter(JSON);
        return MockMvcBuilders.standaloneSetup(new OpsController(http, properties, mock(BizMockClient.class), JSON,
                mock(LlmFaultInjector.class), mock(CacheService.class), mock(KbEpoch.class),
                mock(TokenBudget.class), mock(HybridRetriever.class), mock(DevDefaultsPolicy.class), writer))
                .build();
    }

    private void stubTicketService(String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(response).when(http).send(any(HttpRequest.class), any());
        doReturn(200).when(response).statusCode();
        doReturn(body).when(response).body();
    }

    private HttpRequest captured() {
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        try {
            verify(http, org.mockito.Mockito.atLeastOnce()).send(sent.capture(), any());
        } catch (Exception verified) {
            throw new IllegalStateException(verified);
        }
        return sent.getValue();
    }
}