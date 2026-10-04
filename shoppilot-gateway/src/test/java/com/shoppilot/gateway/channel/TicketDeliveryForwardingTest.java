package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.FallbackReason;
import com.shoppilot.gateway.agent.FallbackService;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 建单时把渠道与投递目标带给工单服务（round26 票 86 / ADR 0059）。
 *
 * <p>断的是**网关发出的那个请求体**：工单服务那侧的两列由 {@code TicketDeliveryTargetTest} 断，
 * 两边合起来才是「渠道与目标真的到了工单」。
 */
class TicketDeliveryForwardingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GatewayProperties.Ticket TICKET =
            new GatewayProperties.Ticket("http://127.0.0.1:8092", "ticket-internal",
                    Duration.ofSeconds(2), Duration.ofSeconds(2));

    private final HttpClient http = mock(HttpClient.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final GatewayProperties properties = mock(GatewayProperties.class);

    TicketDeliveryForwardingTest() {
        when(properties.ticket()).thenReturn(TICKET);
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        ChannelContext.clear();
    }

    @Test
    @DisplayName("降级单带上渠道与回我地址")
    void escalationCarriesChannelAndContact() throws Exception {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-1"));
        ChannelContext.set(Channel.EMAIL, "buyer@example.com");
        stubTicketService();
        fallbackService().escalate(FallbackReason.TOOL_UNAVAILABLE, "查一下", "t", null);

        JsonNode payload = lastSentPayload();
        assertThat(payload.path("channel").asText()).isEqualTo("email");
        assertThat(payload.path("contact").asText()).isEqualTo("buyer@example.com");
    }

    @Test
    @DisplayName("web 渠道：带渠道、不带 contact（买家就在浏览器里等，没有「送回去」这件事）")
    void webEscalationHasChannelButNoContact() throws Exception {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-2"));
        ChannelContext.set(Channel.WEB);
        stubTicketService();
        fallbackService().escalate(FallbackReason.TOOL_UNAVAILABLE, "查一下", "t", null);

        JsonNode payload = lastSentPayload();
        assertThat(payload.path("channel").asText()).isEqualTo("web");
        assertThat(payload.has("contact")).as("没有目标就别发一个空字段过去").isFalse();
    }

    @Test
    @DisplayName("邮件回执单：两格都有值，且 transcript 里那一句【回执渠道】保留")
    void emailReceiptCarriesBothColumnsAndKeepsTheTranscriptLine() throws Exception {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-3"));
        stubTicketService();

        new EmailReceiptWriter(http, JSON, properties).writeReceipt("查一下", "已受理", "buyer@example.com");

        JsonNode payload = lastSentPayload();
        assertThat(payload.path("channel").asText()).isEqualTo("email");
        assertThat(payload.path("contact").asText()).isEqualTo("buyer@example.com");
        assertThat(payload.path("transcript").asText())
                .as("那是人类可读的历史文本，为加一列而删它等于改既有内容")
                .contains("【回执渠道】buyer@example.com");
    }

    private FallbackService fallbackService() {
        return new FallbackService(http, JSON, properties, registry, mock(StringRedisTemplate.class));
    }

    private void stubTicketService() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(response).when(http).send(any(HttpRequest.class), any());
        doReturn(201).when(response).statusCode();
        doReturn("{\"id\":\"T-1\"}").when(response).body();
    }

    private JsonNode lastSentPayload() throws Exception {
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, org.mockito.Mockito.atLeastOnce()).send(sent.capture(), any());
        return JSON.readTree(bodyOf(sent.getValue()));
    }

    /** {@code BodyPublisher} 没有便捷的取全文方法，订阅一次收集字节即可。 */
    private static String bodyOf(HttpRequest request) {
        StringBuilder buffer = new StringBuilder();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                buffer.append(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public void onError(Throwable throwable) {
                throw new IllegalStateException(throwable);
            }

            @Override
            public void onComplete() {
            }
        });
        return buffer.toString();
    }
}