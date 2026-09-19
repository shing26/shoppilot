package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.agent.BizMockClient;
import com.shoppilot.gateway.agent.FallbackService;
import com.shoppilot.gateway.agent.PromptCatalog;
import com.shoppilot.gateway.feedback.FeedbackService;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.config.DevDefaultsPolicy;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.config.OpsAccess;
import com.shoppilot.gateway.identity.RequestTrace;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.llm.LlmFaultInjector;
import com.shoppilot.gateway.llm.TokenBudget;
import com.shoppilot.gateway.ratelimit.RateLimitService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REST 侧网关自产错误只有一个形状（ADR 0028），而且这形状不改任何 status code。
 *
 * <p>缺陷本相：advice 之前 REST 侧零个统一出口，错误体散在几处字符串拼接里，同一个失败在不同端点长成
 * 不同样子；更要紧的是代理透传回来的下游体与网关自己拒的在报文上分不开，而本仓全部降级判断都建立在
 * 「是谁拒的」这个信息上。所以这一票既要统一形状，又必须逐字节不动透传体。
 *
 * <p>用 standaloneSetup 而不是 @SpringBootTest：本仓不起容器（README 的测试口径），这三条判据
 * （形状、码不变、透传不动）都不需要容器。
 */
class RestErrorEnvelopeTest {

    private static final String OPS_TOKEN = "unit-ops-token";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApiErrorWriter writer = new ApiErrorWriter(JSON);
    private final GatewayProperties properties = mock(GatewayProperties.class);
    private final CacheService cacheService = mock(CacheService.class);
    private final LlmFaultInjector faultInjector = mock(LlmFaultInjector.class);
    private final HttpClient http = mock(HttpClient.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    RestErrorEnvelopeTest() {
        when(properties.ops()).thenReturn(new GatewayProperties.Ops(true, OPS_TOKEN));
        when(properties.bizmock()).thenReturn(new GatewayProperties.BizMock(
                "http://127.0.0.1:8091", "internal", Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }

    @AfterEach
    void wipeTrace() {
        MDC.remove(RequestTrace.TRACE_ID);
    }

    private MockMvc mvc() {
        OpsController ops = new OpsController(http, properties, mock(BizMockClient.class), JSON,
                faultInjector, cacheService, mock(KbEpoch.class), mock(TokenBudget.class),
                mock(HybridRetriever.class), mock(DevDefaultsPolicy.class), writer);
        ChatController chat = new ChatController(mock(AgentStateMachine.class), JSON, registry, new PromptCatalog(),
                mock(FeedbackService.class), new ChatAdmission(cacheService, mock(RateLimitService.class),
                        mock(FallbackService.class), registry));
        return MockMvcBuilders.standaloneSetup(ops, chat)
                .setControllerAdvice(new GatewayErrorHandler(writer))
                .build();
    }

    /** MockMvc 那份响应按容器默认字符集解字节，中文会花；写出去的是 UTF-8，判据就得按 UTF-8 读回来。 */
    private static String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode envelope(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(text(result));
        assertThat(body.fieldNames()).toIterable().containsExactly("code", "message", "traceId");
        return body;
    }

    @Test
    @DisplayName("超长输入拿到可显示的校验文案，不再是裸 Spring 错误体")
    void validationFailureReturnsReadableMessage() throws Exception {
        MDC.put(RequestTrace.TRACE_ID, "trace-validation");

        MvcResult result = mvc().perform(post("/api/v1/support/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"" + "问".repeat(600) + "\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = envelope(result);
        assertThat(body.get("code").asText()).isEqualTo(ApiError.VALIDATION_FAILED);
        assertThat(body.get("message").asText()).contains("问题太长").contains("500");
        assertThat(body.get("traceId").asText()).isEqualTo("trace-validation");
    }

    @Test
    @DisplayName("空问题给另一句人话：两条约束各有文案，不共用一句「参数不合法」")
    void blankQueryHasItsOwnMessage() throws Exception {
        MvcResult result = mvc().perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"  \"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(envelope(result).get("message").asText()).contains("问题不能为空");
    }

    @Test
    @DisplayName("流式那条通道也一样：Accept 是 event-stream，400 的 JSON 信封照样出得来")
    void streamEndpointSendsTheSameEnvelopeForInvalidInput() throws Exception {
        // 调试台的超长输入打在 /chat/stream 上。这一格钉的是「advice 显式带的 JSON 内容类型
        // 不被请求的 Accept: text/event-stream 换成 406」——换成了就没有 message 可回显，票 26 那条断言会空转。
        MvcResult result = mvc().perform(post("/api/v1/support/chat/stream")
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"" + "退".repeat(600) + "\"}"))
            .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(envelope(result).get("message").asText()).contains("问题太长");
    }

    @Test
    @DisplayName("没人接的运行时异常：500 仍是 500，但报文有 code 与 traceId（依赖弄残那档的裸 500 收进这里）")
    void unexpectedFailureGetsCodeNotBareBody() throws Exception {
        MDC.put(RequestTrace.TRACE_ID, "trace-500");
        when(cacheService.flush()).thenThrow(new RuntimeException("qdrant unreachable"));

        MvcResult result = mvc().perform(post("/api/v1/support/ops/cache/flush")
                        .header("X-Ops-Token", OPS_TOKEN))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        JsonNode body = envelope(result);
        assertThat(body.get("code").asText()).isEqualTo(ApiError.INTERNAL_ERROR);
        assertThat(body.get("traceId").asText()).isEqualTo("trace-500");
        // 内部细节不许进报文：依赖地址与堆栈只在带链路号的日志里
        assertThat(body.get("message").asText()).doesNotContain("qdrant").doesNotContain("8091");
    }

    @Test
    @DisplayName("运维 403 的 body 被 HTTP 层钉住：令牌不匹配出 ops.token_mismatch（票 21 的 S5）")
    void opsTokenMismatchPinsBody() throws Exception {
        MvcResult result = mvc().perform(get("/api/v1/support/ops/stats")
                        .header("X-Ops-Token", "wrong-token"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        JsonNode body = envelope(result);
        assertThat(body.get("code").asText()).isEqualTo(OpsAccess.TOKEN_MISMATCH.code());
        assertThat(body.get("message").asText()).isEqualTo(OpsAccess.TOKEN_MISMATCH.message());
    }

    @Test
    @DisplayName("开关关闭与令牌不匹配仍是两件事，两个 code 各回各的（不新增第三种含义）")
    void opsDisabledKeepsItsOwnCode() throws Exception {
        when(properties.ops()).thenReturn(new GatewayProperties.Ops(false, OPS_TOKEN));

        MvcResult result = mvc().perform(get("/api/v1/support/ops/stats")
                        .header("X-Ops-Token", OPS_TOKEN))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(envelope(result).get("code").asText()).isEqualTo(OpsAccess.DISABLED.code());
    }

    @Test
    @DisplayName("代理透传的下游体逐字节不变，连下游自己的错误形状也不接管")
    void proxyBodyIsByteForByte() throws Exception {
        String downstream = "{\"error\":\"order not found\",\"detail\":\"带\\\"引号\\\"与中文\",\"z\":2}";
        stubDownstream(404, downstream);

        MvcResult result = mvc().perform(get("/api/v1/support/ops/tenants")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(text(result)).isEqualTo(downstream);
    }

    @Test
    @DisplayName("网关自己拒的与下游拒的当场分得清：一边有 code，一边没有")
    void gatewayVersusDownstreamRejection() throws Exception {
        stubDownstream(400, "{\"error\":\"downstream says no\"}");
        JsonNode viaDownstream = JSON.readTree(text(mvc().perform(get("/api/v1/support/ops/stats")
                .header("X-Ops-Token", OPS_TOKEN)).andReturn()));

        assertThat(viaDownstream.has("code")).isFalse();
        assertThat(viaDownstream.get("error").asText()).isEqualTo("downstream says no");

        doThrow(new java.io.IOException("connect refused")).when(http).send(any(HttpRequest.class), any());
        MvcResult unreachable = mvc().perform(get("/api/v1/support/ops/stats")
                .header("X-Ops-Token", OPS_TOKEN)).andReturn();

        assertThat(unreachable.getResponse().getStatus()).isEqualTo(502);
        assertThat(envelope(unreachable).get("code").asText()).isEqualTo(ApiError.DOWNSTREAM_UNREACHABLE);
    }

    @Test
    @DisplayName("Spring 自己那批客户端错误不被 advice 吸成 500：405 仍是 405")
    void springStandardErrorsKeepTheirCode() throws Exception {
        MvcResult result = mvc().perform(get("/api/v1/support/chat")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(405);
        assertThat(text(result)).doesNotContain(ApiError.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("请求体读不出来：那是客户端的 400，advice 不许把它抬成 500（勾 2 的另一半）")
    void unreadableBodyKeepsItsStatusNotJustAnyStatus() throws Exception {
        // 405 那格挡的是 ServletException 那一支；这一支（HttpMessageNotReadableException）是
        // NestedRuntimeException 的后代，catch-all 的 RuntimeException 处理器会把它吸进去。
        // 收进统一形状可以，改状态码不行：报文读不出来是调用方的错，不是网关的错。
        MvcResult result = mvc().perform(post("/api/v1/support/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":"))
            .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(envelope(result).get("code").asText()).isEqualTo(ApiError.INVALID_REQUEST);
        assertThat(envelope(result).get("message").asText()).contains("无法解析");
    }

    @Test
    @DisplayName("message 里的引号与换行不再产出非法 JSON（原先那里是拼字符串）")
    void messageIsSerialisedNotConcatenated() throws Exception {
        doThrow(new IllegalArgumentException("模式 \"x\" 不存在\n第二行"))
                .when(faultInjector).configure(any());

        MvcResult result = mvc().perform(put("/api/v1/support/ops/llm-fault")
                        .header("X-Ops-Token", OPS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"x\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = envelope(result);
        assertThat(body.get("code").asText()).isEqualTo(ApiError.INVALID_REQUEST);
        assertThat(body.get("message").asText()).contains("\"x\"").contains("第二行");
    }

    @Test
    @DisplayName("没有链路号时三键仍在，traceId 回 null：形状不许随场景变")
    void envelopeShapeDoesNotDependOnTrace() throws Exception {
        MvcResult result = mvc().perform(get("/api/v1/support/ops/stats")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(envelope(result).get("traceId").isNull()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private void stubDownstream(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        // send() 的返回类型参数由 BodyHandler 推出来，when(...).thenReturn(...) 会撞上
        // HttpResponse<String> 与 HttpResponse<Object> 不能转换；doReturn 才不管泛型。
        doReturn(response).when(http).send(any(HttpRequest.class), any());
    }
}
